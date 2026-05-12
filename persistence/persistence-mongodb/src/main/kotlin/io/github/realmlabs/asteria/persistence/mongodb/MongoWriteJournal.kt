package io.github.realmlabs.asteria.persistence.mongodb

import org.bson.Document
import org.bson.json.JsonMode
import org.bson.json.JsonWriterSettings
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Local write-ahead journal for Mongo dirty writes.
 *
 * The journal records set/unset operations before they are flushed to Mongo.
 * Replaying these operations is idempotent, so recovery can safely retry entries
 * whose Mongo write may have succeeded but whose journal checkpoint was not
 * advanced before a crash.
 */
interface MongoWriteJournal : AutoCloseable {
    /**
     * Appends one dirty operation and returns its sequence, or null when journaling is disabled.
     */
    fun append(op: MongoChangeOp): Long?

    /**
     * Acknowledges journal entries whose writes reached Mongo.
     *
     * Implementations advance the durable checkpoint only across contiguous acknowledged sequences.
     */
    fun ack(sequences: Iterable<Long>)

    /**
     * Returns entries after the durable checkpoint for startup replay.
     */
    fun recover(): List<MongoJournalEntry>

    override fun close() = Unit
}

object NoopMongoWriteJournal : MongoWriteJournal {
    override fun append(op: MongoChangeOp): Long? = null

    override fun ack(sequences: Iterable<Long>) = Unit

    override fun recover(): List<MongoJournalEntry> = emptyList()
}

/**
 * One WAL entry with its monotonic sequence number.
 */
data class MongoJournalEntry(
    val sequence: Long,
    val op: MongoChangeOp,
)

enum class MongoJournalWriteMode {
    /**
     * Writes every WAL line on the caller thread.
     */
    SYNC,

    /**
     * Enqueues WAL lines to one background writer thread.
     *
     * This mode reduces actor-thread blocking, but a crash can still lose dirty data that was enqueued in memory and not
     * written to the WAL yet. Use [SYNC] when the WAL must be a strict durability boundary.
     */
    BUFFERED,
}

data class MongoJournalPolicy(
    /**
     * Enables local WAL creation.
     */
    val enabled: Boolean = false,
    /**
     * Directory containing `active.wal` and `checkpoint`.
     */
    val directory: Path = Path.of("data", "journal", "mongodb"),
    /**
     * Chooses caller-thread writes or background buffered writes.
     */
    val writeMode: MongoJournalWriteMode = MongoJournalWriteMode.SYNC,
    /**
     * Forces the WAL channel after append or background batch write.
     */
    val forceOnAppend: Boolean = true,
    /**
     * Replays uncheckpointed WAL entries from [MongoJournalRuntime.recoverOnStart].
     */
    val recoverOnStart: Boolean = true,
    /**
     * WAL size at which checkpointed entries may be compacted away.
     */
    val compactThresholdBytes: Long = 64L * 1024 * 1024,
    /**
     * Maximum queued lines for [MongoJournalWriteMode.BUFFERED].
     */
    val bufferedQueueCapacity: Int = 8192,
    /**
     * Background writer poll interval for [MongoJournalWriteMode.BUFFERED].
     */
    val bufferedPollMillis: Long = 50,
)

/**
 * JSON-lines file implementation of [MongoWriteJournal].
 *
 * Unlike [MongoPendingWriteQueue], a journal instance is expected to be shared by multiple row runtimes on one node, so
 * its append/checkpoint state is synchronized internally.
 */
class FileMongoWriteJournal(
    private val policy: MongoJournalPolicy,
) : MongoWriteJournal {
    private val files = MongoJournalFiles(policy.directory)
    private val channel: FileChannel
    private val acknowledged: MutableSet<Long> = linkedSetOf()
    private var checkpoint: Long = files.readCheckpoint()
    private var nextSequence: Long

    init {
        Files.createDirectories(policy.directory)
        channel = openWalChannel(files.walFile)
        nextSequence = maxOf(checkpoint, files.readMaxSequence()) + 1
    }

    @Synchronized
    override fun append(op: MongoChangeOp): Long {
        val sequence = nextSequence++
        writeLines(listOf(MongoJournalCodec.encodeLine(MongoJournalEntry(sequence, op))))
        if (policy.forceOnAppend) {
            channel.force(false)
        }
        return sequence
    }

    @Synchronized
    override fun ack(sequences: Iterable<Long>) {
        sequences.forEach { sequence ->
            if (sequence > checkpoint) {
                acknowledged += sequence
            }
        }
        if (advanceCheckpoint()) {
            compactIfNeeded()
        }
    }

    @Synchronized
    override fun recover(): List<MongoJournalEntry> {
        return files.readEntriesAfter(checkpoint)
    }

    @Synchronized
    override fun close() {
        channel.close()
    }

    private fun advanceCheckpoint(): Boolean {
        var advanced = false
        while (acknowledged.remove(checkpoint + 1)) {
            checkpoint += 1
            advanced = true
        }
        if (advanced) {
            files.writeCheckpoint(checkpoint)
        }
        return advanced
    }

    private fun compactIfNeeded() {
        if (policy.compactThresholdBytes <= 0) return
        if (!Files.exists(files.walFile) || Files.size(files.walFile) < policy.compactThresholdBytes) return

        val liveEntries = files.readEntriesAfter(checkpoint)
        channel.truncate(0)
        channel.position(0)
        writeLines(liveEntries.map(MongoJournalCodec::encodeLine))
        channel.force(false)
    }

    private fun writeLines(lines: Iterable<String>) {
        lines.forEach { line ->
            val buffer = StandardCharsets.UTF_8.encode("$line\n")
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
        }
    }
}

/**
 * Buffered WAL writer for workloads where caller-thread blocking matters more than strict WAL durability.
 */
class BufferedFileMongoWriteJournal(
    private val policy: MongoJournalPolicy,
) : MongoWriteJournal {
    private data class PendingLine(val sequence: Long, val line: String)

    private val files = MongoJournalFiles(policy.directory)
    private val channel: FileChannel
    private val pendingLines = LinkedBlockingQueue<PendingLine>(policy.bufferedQueueCapacity)
    private val acknowledged: MutableSet<Long> = linkedSetOf()
    private var checkpoint: Long = files.readCheckpoint()
    private var nextSequence: Long
    private var writtenSequence: Long
    private var closing = false
    private var writerFailure: Throwable? = null
    private val writer: Thread

    init {
        Files.createDirectories(policy.directory)
        channel = openWalChannel(files.walFile)
        val maxSequence = files.readMaxSequence()
        writtenSequence = maxOf(checkpoint, maxSequence)
        nextSequence = writtenSequence + 1
        writer = thread(
            start = true,
            isDaemon = true,
            name = "asteria-mongo-wal-writer-${policy.directory.fileName}",
        ) {
            runCatching { writerLoop() }
                .onFailure { error ->
                    synchronized(this) {
                        writerFailure = error
                        closing = true
                    }
                }
        }
    }

    override fun append(op: MongoChangeOp): Long {
        return synchronized(this) {
            ensureWriterHealthy()
            check(!closing) { "Mongo write journal is closing" }
            val sequence = nextSequence++
            val pendingLine = PendingLine(sequence, MongoJournalCodec.encodeLine(MongoJournalEntry(sequence, op)))
            check(pendingLines.offer(pendingLine)) {
                "Mongo write journal buffer is full: capacity=${policy.bufferedQueueCapacity}"
            }
            sequence
        }
    }

    @Synchronized
    override fun ack(sequences: Iterable<Long>) {
        ensureWriterHealthy()
        sequences.forEach { sequence ->
            if (sequence > checkpoint) {
                acknowledged += sequence
            }
        }
        advanceCheckpoint()
    }

    @Synchronized
    override fun recover(): List<MongoJournalEntry> {
        ensureWriterHealthy()
        return files.readEntriesAfter(checkpoint)
    }

    override fun close() {
        synchronized(this) {
            closing = true
        }
        writer.join()
        synchronized(this) {
            channel.close()
        }
    }

    private fun ensureWriterHealthy() {
        writerFailure?.let { error ->
            throw IllegalStateException("Mongo write journal background writer failed", error)
        }
    }

    private fun writerLoop() {
        while (true) {
            val first = pendingLines.poll(policy.bufferedPollMillis, TimeUnit.MILLISECONDS)
            val batch = mutableListOf<PendingLine>()
            if (first != null) {
                batch += first
                pendingLines.drainTo(batch)
                synchronized(this) {
                    writeBatch(batch)
                    writtenSequence = batch.last().sequence
                    if (policy.forceOnAppend) {
                        channel.force(false)
                    }
                    advanceCheckpoint()
                    compactIfNeeded()
                }
            }

            synchronized(this) {
                if (closing && pendingLines.isEmpty()) {
                    channel.force(false)
                    return
                }
            }
        }
    }

    private fun advanceCheckpoint(): Boolean {
        var advanced = false
        while (checkpoint + 1 <= writtenSequence && acknowledged.remove(checkpoint + 1)) {
            checkpoint += 1
            advanced = true
        }
        if (advanced) {
            files.writeCheckpoint(checkpoint)
        }
        return advanced
    }

    private fun compactIfNeeded() {
        if (policy.compactThresholdBytes <= 0) return
        if (!Files.exists(files.walFile) || Files.size(files.walFile) < policy.compactThresholdBytes) return

        val liveEntries = files.readEntriesAfter(checkpoint)
        channel.truncate(0)
        channel.position(0)
        writeBatch(liveEntries.map { PendingLine(it.sequence, MongoJournalCodec.encodeLine(it)) })
        channel.force(false)
    }

    private fun writeBatch(batch: Iterable<PendingLine>) {
        batch.forEach { pending ->
            val buffer = StandardCharsets.UTF_8.encode("${pending.line}\n")
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
        }
    }
}

private class MongoJournalFiles(
    private val directory: Path,
) {
    val walFile: Path = directory.resolve("active.wal")
    private val checkpointFile: Path = directory.resolve("checkpoint")

    fun readCheckpoint(): Long {
        if (!Files.exists(checkpointFile)) return 0
        return Files.readString(checkpointFile).trim().toLongOrNull() ?: 0
    }

    fun readMaxSequence(): Long {
        if (!Files.exists(walFile)) return 0
        return Files.readAllLines(walFile, StandardCharsets.UTF_8)
            .asSequence()
            .filter { it.isNotBlank() }
            .map(MongoJournalCodec::decodeLine)
            .map { it.sequence }
            .maxOrNull() ?: 0
    }

    fun readEntriesAfter(checkpoint: Long): List<MongoJournalEntry> {
        if (!Files.exists(walFile)) return emptyList()
        return Files.readAllLines(walFile, StandardCharsets.UTF_8)
            .asSequence()
            .filter { it.isNotBlank() }
            .map(MongoJournalCodec::decodeLine)
            .filter { it.sequence > checkpoint }
            .toList()
    }

    fun writeCheckpoint(sequence: Long) {
        val tmp = checkpointFile.resolveSibling("${checkpointFile.fileName}.tmp")
        Files.writeString(tmp, sequence.toString(), StandardCharsets.UTF_8)
        try {
            Files.move(tmp, checkpointFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, checkpointFile, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private object MongoJournalCodec {
    private val jsonWriterSettings: JsonWriterSettings = JsonWriterSettings.builder()
        .outputMode(JsonMode.EXTENDED)
        .build()

    fun encodeLine(entry: MongoJournalEntry): String {
        return encode(entry).toJson(jsonWriterSettings)
    }

    fun decodeLine(line: String): MongoJournalEntry {
        return decode(Document.parse(line))
    }

    private fun encode(entry: MongoJournalEntry): Document {
        val op = entry.op
        val document = Document()
            .append("seq", entry.sequence)
            .append("collection", op.key.collection)
            .append("documentId", op.key.documentId)
        return when (op) {
            is MongoChangeOp.Set -> document
                .append("op", "set")
                .append("fieldPath", op.path.fieldPath)
                .append("value", mongoValueOf(op.value))

            is MongoChangeOp.Unset -> document
                .append("op", "unset")
                .append("fieldPath", op.path.fieldPath)

            is MongoChangeOp.Delete -> document.append("op", "delete")
        }
    }

    private fun decode(document: Document): MongoJournalEntry {
        val sequence = (document.get("seq") as Number).toLong()
        val key = MongoDocumentKey(
            collection = document.getString("collection"),
            documentId = document.get("documentId"),
        )
        val op = when (document.getString("op")) {
            "set" -> MongoChangeOp.Set(key.path(document.getString("fieldPath")), document.get("value"))
            "unset" -> MongoChangeOp.Unset(key.path(document.getString("fieldPath")))
            "delete" -> MongoChangeOp.Delete(key)
            else -> error("unknown Mongo journal op ${document.getString("op")}")
        }
        return MongoJournalEntry(sequence, op)
    }
}

private fun openWalChannel(walFile: Path): FileChannel {
    return FileChannel.open(
        walFile,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.APPEND,
    )
}

fun MongoJournalPolicy.createJournal(): MongoWriteJournal {
    if (!enabled) return NoopMongoWriteJournal
    return when (writeMode) {
        MongoJournalWriteMode.SYNC -> FileMongoWriteJournal(this)
        MongoJournalWriteMode.BUFFERED -> BufferedFileMongoWriteJournal(this)
    }
}
