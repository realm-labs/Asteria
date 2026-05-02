package io.github.mikai233.asteria.persistence.mongodb

import com.mongodb.bulk.BulkWriteResult
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.mikai233.asteria.persistence.DataLease
import io.github.mikai233.asteria.persistence.DataLeaseAware

/**
 * Dirty tracking runtime for one Mongo document.
 */
class MongoTrackedDocumentRuntime(
    private val collectionName: String,
    private val documentId: Any?,
    database: MongoDatabase,
    journal: MongoWriteJournal = NoopMongoWriteJournal,
    onDirty: () -> Unit = {},
) : DataLeaseAware {
    val queue: MongoPendingWriteQueue = MongoPendingWriteQueue(journal = journal, onDirty = onDirty)
    private val flusher: MongoPendingWriteFlusher = MongoPendingWriteFlusher(queue, database, journal)
    private var lease: DataLease? = null

    fun context(): MongoTrackContext {
        return MongoTrackContext(collectionName, documentId, queue) { lease }
    }

    override fun bindLease(lease: DataLease) {
        this.lease = lease
    }

    fun enqueueCreated(document: MongoTrackedDocument<*, *>) {
        val persistentValue = document.toMongoValue()
        require(persistentValue is Map<*, *>) {
            "Tracked Mongo document ${document::class.qualifiedName} persistent value must be a map"
        }
        persistentValue.entries.forEach { (fieldPath, fieldValue) ->
            if (fieldPath.toString() != "_id") {
                queue.enqueue(MongoChangeOp.Set(MongoPath(collectionName, document.id, fieldPath.toString()), fieldValue))
            }
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
}
