package io.github.mikai233.asteria.persistence.mongodb

import com.mongodb.client.model.Filters.eq
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.mikai233.asteria.persistence.AutoFlushMemData
import io.github.mikai233.asteria.persistence.DataScope
import io.github.mikai233.asteria.persistence.Entity
import kotlinx.coroutines.flow.firstOrNull
import kotlin.reflect.KClass

/**
 * Base class for one tracked Mongo document owned by an actor.
 */
abstract class MongoTrackedDocumentData<ID : Any, E : Entity<ID>, T : MongoTrackedDocument<ID, E>>(
    protected val scope: DataScope<ID>,
    protected val collectionName: String,
    private val entityType: KClass<E>,
    private val wrapper: (MongoTrackContext, E) -> T,
    database: MongoDatabase = scope.services.get(),
) : AutoFlushMemData {
    protected val queue: MongoPendingWriteQueue = MongoPendingWriteQueue()
    protected val flusher: MongoPendingWriteFlusher = MongoPendingWriteFlusher(queue, database)
    protected val collection: MongoCollection<E> = database.getCollection(collectionName, entityType.java)

    var value: T? = null
        private set

    override suspend fun load() {
        val loaded = collection.find(eq("_id", scope.entityId)).firstOrNull()
        value = loaded?.let(::attachLoaded)
    }

    protected fun attachLoaded(entity: E): T {
        return wrapper(trackContext(entity.id), entity)
    }

    protected fun createTracked(entity: E): T {
        require(value == null) { "tracked document $collectionName:${entity.id} is already loaded" }
        val tracked = attachLoaded(entity)
        enqueueCreated(tracked)
        value = tracked
        return tracked
    }

    protected fun requireValue(): T {
        return requireNotNull(value) { "tracked document $collectionName:${scope.entityId} is not loaded" }
    }

    override suspend fun tick() {
        flush()
    }

    override suspend fun flush(): Boolean {
        return runCatching { flusher.flush() }.isSuccess
    }

    private fun trackContext(documentId: Any?): MongoTrackContext {
        return MongoTrackContext(collectionName, documentId, queue)
    }

    private fun enqueueCreated(document: T) {
        val persistentValue = document.toMongoValue()
        require(persistentValue is Map<*, *>) {
            "Tracked Mongo document ${document::class.qualifiedName} persistent value must be a map"
        }
        val entries = persistentValue.entries.map { (key, value) -> key.toString() to value }
        entries.forEach { (fieldPath, fieldValue) ->
            if (fieldPath != "_id") {
                queue.enqueue(MongoChangeOp.Set(MongoPath(collectionName, document.id, fieldPath), fieldValue))
            }
        }
    }
}
