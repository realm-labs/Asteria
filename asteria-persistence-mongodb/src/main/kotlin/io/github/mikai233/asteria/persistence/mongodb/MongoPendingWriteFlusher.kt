package io.github.mikai233.asteria.persistence.mongodb

import com.mongodb.bulk.BulkWriteResult
import com.mongodb.client.model.BulkWriteOptions
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates.combine
import com.mongodb.client.model.Updates.inc
import com.mongodb.client.model.Updates.set
import com.mongodb.client.model.Updates.unset
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import org.bson.Document
import org.bson.conversions.Bson

/**
 * Flushes actor-local pending writes as unordered Mongo bulk updates.
 */
class MongoPendingWriteFlusher(
    private val queue: MongoPendingWriteQueue,
    private val database: MongoDatabase,
    private val idField: String = "_id",
) {
    suspend fun flush(): List<BulkWriteResult> {
        val writes = queue.drain()
        if (writes.isEmpty()) return emptyList()

        val writesByCollection = writes.groupBy { it.key.collection }
        val successfulCollections = mutableSetOf<String>()
        val results = mutableListOf<BulkWriteResult>()

        try {
            writesByCollection.forEach { (collectionName, collectionWrites) ->
                val collection = database.getCollection<Document>(collectionName)
                val models = collectionWrites.map { write ->
                    UpdateOneModel<Document>(
                        eq(idField, write.key.documentId),
                        write.update(),
                        UpdateOptions().upsert(write.upsert),
                    )
                }
                results += collection.bulkWrite(models, BulkWriteOptions().ordered(false))
                successfulCollections += collectionName
            }
            return results
        } catch (error: Throwable) {
            val notFlushed = writesByCollection
                .filterKeys { collection -> collection !in successfulCollections }
                .values
                .flatten()
            queue.requeue(notFlushed)
            throw error
        }
    }

    private fun MongoPendingWrite.update(): Bson {
        val updates = mutableListOf<Bson>()
        sets.forEach { (path, value) -> updates += set(path, mongoValueOf(value)) }
        unsets.forEach { path -> updates += unset(path) }
        incs.forEach { (path, delta) -> updates += inc(path, delta) }
        require(updates.isNotEmpty()) { "Mongo pending write must contain at least one update" }
        return combine(updates)
    }
}
