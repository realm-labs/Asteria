package io.github.mikai233.asteria.persistence.mongodb

import com.mongodb.bulk.BulkWriteResult
import com.mongodb.client.model.BulkWriteOptions
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates.*
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.mikai233.asteria.observability.MetricTags
import io.github.mikai233.asteria.observability.Metrics
import io.github.mikai233.asteria.observability.NoopMetrics
import org.bson.Document
import org.bson.conversions.Bson
import org.slf4j.LoggerFactory

/**
 * Flushes actor-local pending writes as unordered Mongo bulk updates.
 */
class MongoPendingWriteFlusher(
    private val queue: MongoPendingWriteQueue,
    private val database: MongoDatabase,
    private val journal: MongoWriteJournal = NoopMongoWriteJournal,
    private val idField: String = "_id",
    private val metrics: Metrics = NoopMetrics,
) {
    private val logger = LoggerFactory.getLogger(MongoPendingWriteFlusher::class.java)

    suspend fun flush(): List<BulkWriteResult> {
        val writes = queue.drain()
        if (writes.isEmpty()) return emptyList()

        val startedAt = System.nanoTime()
        val writesByCollection = writes.groupBy { it.key.collection }
        val successfulCollections = mutableSetOf<String>()
        val results = mutableListOf<BulkWriteResult>()

        try {
            writesByCollection.forEach { (collectionName, collectionWrites) ->
                val tags = MetricTags.of("collection" to collectionName)
                val collection = database.getCollection<Document>(collectionName)
                val models = collectionWrites.map { write ->
                    UpdateOneModel<Document>(
                        eq(idField, write.key.documentId),
                        write.update(),
                        UpdateOptions().upsert(write.upsert),
                    )
                }
                results += collection.bulkWrite(models, BulkWriteOptions().ordered(false))
                metrics.counter("asteria.persistence.mongodb.flush.documents.total", tags)
                    .increment(collectionWrites.size.toLong())
                journal.ack(collectionWrites.flatMap { it.journalSequences })
                successfulCollections += collectionName
            }
            metrics.counter("asteria.persistence.mongodb.flush.total").increment()
            return results
        } catch (error: Throwable) {
            val notFlushed = writesByCollection
                .filterKeys { collection -> collection !in successfulCollections }
                .values
                .flatten()
            queue.requeue(notFlushed)
            metrics.counter("asteria.persistence.mongodb.flush.failed.total").increment()
            logger.error(
                "Mongo pending write flush failed collections={} documents={}",
                writesByCollection.size,
                writes.size,
                error,
            )
            throw error
        } finally {
            metrics.timer("asteria.persistence.mongodb.flush.duration")
                .record((System.nanoTime() - startedAt) / 1_000_000)
        }
    }

    private fun MongoPendingWrite.update(): Bson {
        val updates = mutableListOf<Bson>()
        sets.forEach { (path, value) -> updates += set(path, mongoValueOf(value)) }
        unsets.forEach { path -> updates += unset(path) }
        require(updates.isNotEmpty()) { "Mongo pending write must contain at least one update" }
        return combine(updates)
    }
}
