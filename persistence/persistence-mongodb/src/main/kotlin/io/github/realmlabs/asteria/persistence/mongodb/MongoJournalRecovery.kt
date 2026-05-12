package io.github.realmlabs.asteria.persistence.mongodb

import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import org.slf4j.LoggerFactory

/**
 * Replays uncheckpointed local journal entries into Mongo.
 *
 * The same [MongoWriteJournal] should be reused by the dirty write runtimes after recovery, otherwise recovery and
 * runtime writes can acknowledge different checkpoint state.
 */
class MongoJournalRecovery(
    private val journal: MongoWriteJournal,
    private val database: MongoDatabase,
    private val metrics: Metrics = NoopMetrics,
) {
    private val logger = LoggerFactory.getLogger(MongoJournalRecovery::class.java)

    /**
     * Replays uncheckpointed WAL entries and acknowledges entries that flush successfully.
     *
     * Failures are propagated so callers can stop startup instead of running with uncertain durability state.
     */
    suspend fun recover(): MongoJournalRecoveryResult {
        val startedAt = System.nanoTime()
        val entries = journal.recover()
        if (entries.isEmpty()) {
            metrics.counter("asteria.persistence.mongodb.journal.recovery.total", MetricTags.of("empty" to "true"))
                .increment()
            return MongoJournalRecoveryResult()
        }

        val queue = MongoPendingWriteQueue(journal = NoopMongoWriteJournal)
        entries.forEach(queue::replay)
        val writes = queue.snapshot()
        return try {
            MongoPendingWriteFlusher(queue, database, journal, metrics = metrics).flush()
            val result = MongoJournalRecoveryResult(
                entries = entries.size,
                documents = writes.size,
            )
            metrics.counter("asteria.persistence.mongodb.journal.recovery.total", MetricTags.of("empty" to "false"))
                .increment()
            metrics.counter("asteria.persistence.mongodb.journal.recovery.entries.total")
                .increment(entries.size.toLong())
            logger.info(
                "Mongo journal recovered entries={} documents={}",
                result.entries,
                result.documents,
            )
            result
        } catch (error: Throwable) {
            metrics.counter("asteria.persistence.mongodb.journal.recovery.failed.total").increment()
            logger.error("Mongo journal recovery failed entries={}", entries.size, error)
            throw error
        } finally {
            metrics.timer("asteria.persistence.mongodb.journal.recovery.duration")
                .record((System.nanoTime() - startedAt) / 1_000_000)
        }
    }
}

data class MongoJournalRecoveryResult(
    val entries: Int = 0,
    val documents: Int = 0,
)

/**
 * Node-level Mongo persistence WAL runtime.
 *
 * Create one instance per node/database, call [recoverOnStart] before actors start loading mutable data, then pass
 * [journal] to Mongo tracked documents and tables.
 */
class MongoJournalRuntime(
    val journal: MongoWriteJournal,
    private val database: MongoDatabase,
    private val policy: MongoJournalPolicy,
    private val metrics: Metrics = NoopMetrics,
) : AutoCloseable {
    /**
     * Runs startup recovery when enabled by [MongoJournalPolicy].
     */
    suspend fun recoverOnStart(): MongoJournalRecoveryResult {
        if (!policy.enabled || !policy.recoverOnStart) return MongoJournalRecoveryResult()
        return MongoJournalRecovery(journal, database, metrics).recover()
    }

    override fun close() {
        journal.close()
    }
}

fun MongoJournalPolicy.createRuntime(
    database: MongoDatabase,
    metrics: Metrics = NoopMetrics,
): MongoJournalRuntime {
    return MongoJournalRuntime(createJournal(), database, this, metrics)
}
