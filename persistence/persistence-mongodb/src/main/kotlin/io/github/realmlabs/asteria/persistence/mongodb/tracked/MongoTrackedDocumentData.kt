package io.github.realmlabs.asteria.persistence.mongodb.tracked

import com.mongodb.client.model.Filters.eq
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.realmlabs.asteria.persistence.AutoFlushMemData
import io.github.realmlabs.asteria.persistence.DataScope
import io.github.realmlabs.asteria.persistence.Entity
import io.github.realmlabs.asteria.persistence.ResidentMemData
import io.github.realmlabs.asteria.persistence.mongodb.write.MongoPendingWriteQueue
import io.github.realmlabs.asteria.persistence.mongodb.write.MongoWriteJournal
import io.github.realmlabs.asteria.persistence.mongodb.write.NoopMongoWriteJournal
import kotlinx.coroutines.flow.firstOrNull
import kotlin.reflect.KClass

/**
 * Base class for one tracked Mongo document owned by an actor.
 *
 * The loaded value is a generated or hand-written wrapper. Mutating wrapper properties enqueues Mongo patches
 * immediately; [flush] only drains the runtime queue.
 */
abstract class MongoTrackedDocumentData<ID : Any, E : Entity<ID>, T : MongoTrackedDocument<ID, E>>(
    protected val scope: DataScope<ID>,
    protected val collectionName: String,
    private val entityType: KClass<E>,
    private val wrapper: (MongoTrackContext, E) -> T,
    database: MongoDatabase = scope.services.get(),
    journal: MongoWriteJournal = NoopMongoWriteJournal,
) : ResidentMemData, AutoFlushMemData {
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

    /**
     * Enqueues a document delete and flushes it immediately.
     *
     * A false result leaves [value] attached so the caller can retry later.
     */
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

    override suspend fun drain(): Boolean {
        return flush()
    }
}
