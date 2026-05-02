package io.github.mikai233.asteria.persistence.mongodb

import com.mongodb.bulk.BulkWriteResult
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.mikai233.asteria.observability.MetricTags
import io.github.mikai233.asteria.observability.Metrics
import io.github.mikai233.asteria.observability.NoopMetrics
import io.github.mikai233.asteria.persistence.DataLease
import io.github.mikai233.asteria.persistence.DataLeaseAware
import io.github.mikai233.asteria.persistence.Entity
import io.github.mikai233.asteria.persistence.EntityScanPlan
import io.github.mikai233.asteria.persistence.EntityScanSnapshot
import io.github.mikai233.asteria.persistence.FieldChange

/**
 * Hash-scan dirty tracking runtime for one Mongo document.
 *
 * A scan snapshot records the state that has already been converted into this runtime's pending write queue. It is
 * advanced when changes are enqueued, not when Mongo confirms the write. If a flush fails, [MongoPendingWriteQueue]
 * requeues the pending write, so the runtime does not need to rediscover the same change on the next scan.
 */
class MongoScannedDocumentRuntime<ID : Any, E : Entity<ID>>(
    private val collectionName: String,
    private val documentId: ID,
    private val scanPlan: EntityScanPlan<E>,
    database: MongoDatabase,
    journal: MongoWriteJournal = NoopMongoWriteJournal,
    private val metrics: Metrics = NoopMetrics,
    onDirty: () -> Unit = {},
) : DataLeaseAware {
    val queue: MongoPendingWriteQueue = MongoPendingWriteQueue(journal = journal, onDirty = onDirty)
    private val flusher: MongoPendingWriteFlusher =
        MongoPendingWriteFlusher(queue, database, journal, metrics = metrics)
    private var snapshot: EntityScanSnapshot = EntityScanSnapshot.Empty
    private var lease: DataLease? = null

    override fun bindLease(lease: DataLease) {
        this.lease = lease
    }

    fun attachLoaded(entity: E) {
        snapshot = scanPlan.capture(entity)
    }

    /**
     * Enqueues every scanned field as `$set`.
     *
     * Mongo applies the resulting write as an upsert. This is intentionally not an insert-only operation; callers that
     * need optimistic insert semantics should check existence before creating the entity.
     */
    fun enqueueCreated(entity: E) {
        val current = scanPlan.capture(entity)
        scanPlan.setAll(current, entity).forEach(::enqueue)
        snapshot = current
    }

    fun enqueueDelete() {
        queue.enqueue(MongoChangeOp.Delete(MongoDocumentKey(collectionName, documentId)))
        snapshot = EntityScanSnapshot.Empty
    }

    fun scan(entity: E): MongoScanResult {
        val startedAt = System.nanoTime()
        return try {
            val current = scanPlan.capture(entity)
            val changes = scanPlan.diff(snapshot, current, entity)
            changes.forEach(::enqueue)
            snapshot = current
            recordScan(changes.size, success = true, startedAt)
            MongoScanResult(changes.size)
        } catch (error: Throwable) {
            recordScan(changedFields = 0, success = false, startedAt)
            throw error
        }
    }

    fun pendingWrites(): List<MongoPendingWrite> = queue.snapshot()

    suspend fun flush(): List<BulkWriteResult> {
        lease?.ensureActive()
        return flusher.flush()
    }

    suspend fun flushSafely(): Boolean {
        return runCatching { flush() }.isSuccess
    }

    private fun enqueue(change: FieldChange) {
        queue.enqueue(MongoFieldChangeEncoder.encode(collectionName, documentId, change))
    }

    private fun recordScan(changedFields: Int, success: Boolean, startedAt: Long) {
        val tags = MetricTags.of("collection" to collectionName)
        metrics.counter("asteria.persistence.mongodb.scan.documents.total", tags).increment()
        if (!success) {
            metrics.counter("asteria.persistence.mongodb.scan.documents.failed.total", tags).increment()
        }
        if (changedFields > 0) {
            metrics.counter("asteria.persistence.mongodb.scan.documents.dirty.total", tags).increment()
            metrics.counter("asteria.persistence.mongodb.scan.fields.changed.total", tags).increment(changedFields.toLong())
        }
        metrics.timer("asteria.persistence.mongodb.scan.duration", tags)
            .record((System.nanoTime() - startedAt) / 1_000_000)
    }
}

data class MongoScanResult(
    val changedFields: Int,
) {
    val dirty: Boolean
        get() = changedFields > 0
}
