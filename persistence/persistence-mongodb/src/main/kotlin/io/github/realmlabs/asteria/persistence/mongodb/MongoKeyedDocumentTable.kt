package io.github.realmlabs.asteria.persistence.mongodb

import com.mongodb.client.model.Filters.eq
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.realmlabs.asteria.persistence.*
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import org.bson.Document
import org.bson.conversions.Bson
import java.time.Clock
import java.util.*
import kotlin.reflect.KClass
import kotlin.time.TimeSource

/**
 * Mongo implementation for a row-level table.
 *
 * Each loaded row owns a [MongoTrackedDocumentRuntime], so [flushRow] writes only the dirty Mongo patch accumulated by
 * the row wrapper. Database-side queries return entity snapshots or row keys; callers should re-enter [use] before
 * mutating a candidate row.
 */
abstract class MongoKeyedDocumentTable<ID : Any, E : Entity<ID>, T : MongoTrackedDocument<ID, E>>(
    private val collectionName: String,
    entityType: KClass<E>,
    cachePolicy: RowCachePolicy,
    database: MongoDatabase,
    private val journal: MongoWriteJournal = NoopMongoWriteJournal,
    clock: Clock = Clock.systemUTC(),
) : KeyedDataTable<ID, T>(cachePolicy, clock) {
    protected val collection: MongoCollection<E> = database.getCollection(collectionName, entityType.java)
    private val database: MongoDatabase = database
    private val runtimes: MutableMap<T, MongoTrackedDocumentRuntime> = IdentityHashMap()
    private val rowsById: MutableMap<ID, T> = linkedMapOf()
    private val dirtyRows: DirtyRowQueue<ID> = DirtyRowQueue()

    override suspend fun loadRow(key: ID): T? {
        return collection.find(eq("_id", key)).firstOrNull()?.let(::attach)
    }

    override suspend fun loadAllRows(): Iterable<T> {
        return collection.find(Document()).toList().map(::attach)
    }

    override fun keyOf(row: T): ID = row.id

    override suspend fun flushRow(row: T): Boolean {
        val flushed = runtime(row).flushSafely()
        if (flushed) {
            dirtyRows.markClean(row.id)
        }
        return flushed
    }

    override fun bindLease(row: T, lease: DataLease) {
        runtime(row).bindLease(lease)
        if (row is DataLeaseAware) {
            row.bindLease(lease)
        }
    }

    override fun afterUnload(row: T) {
        runtimes.remove(row)
        rowsById.remove(row.id)
        dirtyRows.remove(row.id)
    }

    suspend fun flushSome(budget: MongoFlushBudget): MongoFlushProgress {
        val start = TimeSource.Monotonic.markNow()
        var attemptedRows = 0
        var flushedRows = 0
        var failedRows = 0
        while (attemptedRows < budget.maxRows && start.elapsedNow() < budget.maxDuration) {
            val id = dirtyRows.next() ?: break
            val row = rowsById[id]
            if (row == null) {
                dirtyRows.markClean(id)
                continue
            }

            attemptedRows += 1
            if (flushRow(row)) {
                flushedRows += 1
            } else {
                failedRows += 1
                dirtyRows.markDirty(id)
            }
        }
        return MongoFlushProgress(attemptedRows, flushedRows, failedRows)
    }

    suspend fun deleteLoaded(key: ID): Boolean {
        val row = rowsById[key] ?: return true
        val runtime = runtime(row)
        runtime.enqueueDelete()
        val deleted = runtime.flushSafely()
        if (deleted) {
            dropLoaded(key)
        }
        return deleted
    }

    /**
     * Deletes [key] even when the row is not loaded in memory.
     */
    suspend fun deleteByKey(key: ID): Boolean {
        if (key in rowsById) {
            return deleteLoaded(key)
        }
        val runtime = MongoTrackedDocumentRuntime(
            collectionName = collectionName,
            documentId = key,
            database = database,
            journal = journal,
        )
        runtime.enqueueDelete()
        return runtime.flushSafely()
    }

    suspend fun queryKeys(filter: Bson = Document()): List<ID> {
        return collection.find(filter).toList().map { it.id }
    }

    /**
     * Queries database-side snapshots and immediately projects them to caller-owned values.
     *
     * Returned raw Mongo entities are not attached to this table's row cache. Mutating them will not be tracked. Prefer
     * this overload for read-only filtering, reporting, or candidate selection.
     */
    suspend fun <T> querySnapshots(filter: Bson = Document(), mapper: (E) -> T): List<T> {
        return collection.find(filter).toList().map(mapper)
    }

    /**
     * Queries detached raw Mongo entities.
     *
     * Mutating returned objects is not tracked. Use [queryKeys] and re-enter [use], or use the mapper overload to return
     * immutable caller-owned snapshots.
     */
    @Deprecated(
        message = "Raw query snapshots are detached and mutable. Use querySnapshots(filter, mapper) or queryKeys + use.",
        replaceWith = ReplaceWith("querySnapshots(filter) { it }"),
    )
    suspend fun querySnapshots(filter: Bson = Document()): List<E> {
        return collection.find(filter).toList()
    }

    protected abstract fun wrap(context: MongoTrackContext, entity: E): T

    protected fun runtime(row: T): MongoTrackedDocumentRuntime {
        return requireNotNull(runtimes[row]) { "Mongo runtime for row ${row.id} is not loaded" }
    }

    private fun attach(entity: E): T {
        val runtime = MongoTrackedDocumentRuntime(
            collectionName = collectionName,
            documentId = entity.id,
            database = database,
            journal = journal,
            onDirty = { dirtyRows.markDirty(entity.id) },
        )
        val row = wrap(runtime.context(), entity)
        runtimes[row] = runtime
        rowsById[entity.id] = row
        return row
    }
}
