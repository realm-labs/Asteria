package io.github.realmlabs.asteria.persistence.mongodb

import com.mongodb.client.model.Filters.eq
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.realmlabs.asteria.persistence.AutoFlushMemData
import io.github.realmlabs.asteria.persistence.DataScope
import io.github.realmlabs.asteria.persistence.Entity
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
    journal: MongoWriteJournal = NoopMongoWriteJournal,
) : AutoFlushMemData {
    protected val runtime: MongoTrackedDocumentRuntime =
        MongoTrackedDocumentRuntime(collectionName, scope.entityId, database, journal)
    protected val queue: MongoPendingWriteQueue
        get() = runtime.queue
    protected val collection: MongoCollection<E> = database.getCollection(collectionName, entityType.java)

    var value: T? = null
        private set

    override suspend fun load() {
        val loaded = collection.find(eq("_id", scope.entityId)).firstOrNull()
        value = loaded?.let(::attachLoaded)
    }

    protected fun attachLoaded(entity: E): T {
        return wrapper(runtime.context(), entity)
    }

    protected fun createTracked(entity: E): T {
        require(value == null) { "tracked document $collectionName:${entity.id} is already loaded" }
        val tracked = attachLoaded(entity)
        runtime.enqueueCreated(tracked)
        value = tracked
        return tracked
    }

    protected fun requireValue(): T {
        return requireNotNull(value) { "tracked document $collectionName:${scope.entityId} is not loaded" }
    }

    protected suspend fun deleteValue(): Boolean {
        if (value == null) return true
        runtime.enqueueDelete()
        val deleted = runtime.flushSafely()
        if (deleted) {
            value = null
        }
        return deleted
    }

    override suspend fun tick() {
        flush()
    }

    override suspend fun flush(): Boolean {
        return runtime.flushSafely()
    }
}
