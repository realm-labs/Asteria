package io.github.realmlabs.asteria.persistence.mongodb.tracked

import com.mongodb.bulk.BulkWriteResult
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import io.github.realmlabs.asteria.persistence.DataLease
import io.github.realmlabs.asteria.persistence.DataLeaseAware
import io.github.realmlabs.asteria.persistence.mongodb.common.MongoDocumentKey
import io.github.realmlabs.asteria.persistence.mongodb.common.MongoPath
import io.github.realmlabs.asteria.persistence.mongodb.write.*

/**
 * Dirty tracking runtime for one Mongo document.
 *
 * Generated wrappers enqueue changes into [queue]. [flush] drains and bulk-writes that queue; on failure the flusher
 * requeues writes that were not known to succeed. A bound lease prevents writes after the owning row is unloaded.
 */
class MongoTrackedDocumentRuntime(
    private val collectionName: String,
    private val documentId: Any?,
    database: MongoDatabase,
    journal: MongoWriteJournal = NoopMongoWriteJournal,
    metrics: Metrics = NoopMetrics,
    onDirty: () -> Unit = {},
) : DataLeaseAware {
    val queue: MongoPendingWriteQueue = MongoPendingWriteQueue(journal = journal, onDirty = onDirty)
    private val flusher: MongoPendingWriteFlusher =
        MongoPendingWriteFlusher(queue, database, journal, metrics = metrics)
    private var lease: DataLease? = null

    fun context(): MongoTrackContext {
        return MongoTrackContext(collectionName, documentId, queue) { lease }
    }

    override fun bindLease(lease: DataLease) {
        this.lease = lease
    }

    /**
     * Enqueues all document fields except `_id` as `$set` operations for an upsert.
     */
    fun enqueueCreated(document: MongoTrackedDocument<*, *>) {
        val persistentValue = document.toMongoValue()
        require(persistentValue is Map<*, *>) {
            "Tracked Mongo document ${document::class.qualifiedName} persistent value must be a map"
        }
        persistentValue.entries.forEach { (fieldPath, fieldValue) ->
            if (fieldPath.toString() != "_id") {
                queue.enqueue(
                    MongoChangeOp.Set(
                        MongoPath(collectionName, document.id, fieldPath.toString()),
                        fieldValue
                    )
                )
            }
        }
    }

    fun enqueueDelete() {
        queue.enqueue(MongoChangeOp.Delete(MongoDocumentKey(collectionName, documentId)))
    }

    fun pendingWrites(): List<MongoPendingWrite> = queue.snapshot()

    suspend fun flush(): List<BulkWriteResult> {
        lease?.ensureActive()
        return flusher.flush()
    }

    suspend fun flushSafely(): Boolean {
        return runCatching { flush() }.isSuccess
    }
}
