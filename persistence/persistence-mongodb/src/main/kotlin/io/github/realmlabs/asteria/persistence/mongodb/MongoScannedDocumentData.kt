package io.github.realmlabs.asteria.persistence.mongodb

import com.mongodb.client.model.Filters.eq
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import io.github.realmlabs.asteria.persistence.AutoFlushMemData
import io.github.realmlabs.asteria.persistence.DataScope
import io.github.realmlabs.asteria.persistence.Entity
import io.github.realmlabs.asteria.persistence.EntityScanPlan
import kotlinx.coroutines.flow.firstOrNull
import kotlin.reflect.KClass

/**
 * Base class for one Mongo document tracked by periodic entity scans.
 *
 * Business code mutates the loaded raw entity. Before flushing, the runtime scans the entity, compares it with the last
 * snapshot, and writes only changed fields.
 *
 * The runtime snapshot means "already converted into pending writes", not "already persisted". Failed flushes keep the
 * pending write queued for retry.
 */
abstract class MongoScannedDocumentData<ID : Any, E : Entity<ID>>(
    protected val scope: DataScope<ID>,
    protected val collectionName: String,
    private val entityType: KClass<E>,
    scanPlan: EntityScanPlan<E>,
    database: MongoDatabase = scope.services.get(),
    journal: MongoWriteJournal = NoopMongoWriteJournal,
    metrics: Metrics = NoopMetrics,
) : AutoFlushMemData {
    protected val runtime: MongoScannedDocumentRuntime<ID, E> =
        MongoScannedDocumentRuntime(collectionName, scope.entityId, scanPlan, database, journal, metrics)
    protected val collection: MongoCollection<E> = database.getCollection(collectionName, entityType.java)

    var value: E? = null
        private set

    override suspend fun load() {
        val loaded = collection.find(eq("_id", scope.entityId)).firstOrNull()
        value = loaded?.also(runtime::attachLoaded)
    }

    protected fun createScanned(entity: E): E {
        require(value == null) { "scanned document $collectionName:${entity.id} is already loaded" }
        runtime.enqueueCreated(entity)
        value = entity
        return entity
    }

    protected fun requireValue(): E {
        return requireNotNull(value) { "scanned document $collectionName:${scope.entityId} is not loaded" }
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
        value?.let { entity -> runtime.scan(entity) }
        return runtime.flushSafely()
    }

    override suspend fun drain(): Boolean {
        return flush()
    }
}
