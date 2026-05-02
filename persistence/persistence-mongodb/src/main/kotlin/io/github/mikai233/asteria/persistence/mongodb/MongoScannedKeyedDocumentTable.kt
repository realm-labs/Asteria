package io.github.mikai233.asteria.persistence.mongodb

import com.mongodb.client.model.Filters.eq
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.mikai233.asteria.observability.MetricTags
import io.github.mikai233.asteria.observability.Metrics
import io.github.mikai233.asteria.observability.NoopMetrics
import io.github.mikai233.asteria.persistence.DataLease
import io.github.mikai233.asteria.persistence.DataLeaseAware
import io.github.mikai233.asteria.persistence.Entity
import io.github.mikai233.asteria.persistence.EntityScanPlan
import io.github.mikai233.asteria.persistence.KeyedDataTable
import io.github.mikai233.asteria.persistence.RowCachePolicy
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import org.bson.Document
import org.bson.conversions.Bson
import java.time.Clock
import java.util.IdentityHashMap
import kotlin.reflect.KClass
import kotlin.time.TimeSource

/**
 * Mongo row table that detects dirty rows through hash scans.
 *
 * Scan snapshots are advanced when changed fields enter the pending write queue. If Mongo flush fails, the queued write
 * is retried later; the row does not need to be marked dirty again by comparing with the old snapshot.
 */
open class MongoScannedKeyedDocumentTable<ID : Any, E : Entity<ID>>(
    private val collectionName: String,
    entityType: KClass<E>,
    private val scanPlan: EntityScanPlan<E>,
    cachePolicy: RowCachePolicy,
    database: MongoDatabase,
    private val journal: MongoWriteJournal = NoopMongoWriteJournal,
    private val metrics: Metrics = NoopMetrics,
    clock: Clock = Clock.systemUTC(),
) : KeyedDataTable<ID, E>(cachePolicy, clock), MongoScannedTable {
    protected val collection: MongoCollection<E> = database.getCollection(collectionName, entityType.java)
    private val database: MongoDatabase = database
    private val runtimes: MutableMap<E, MongoScannedDocumentRuntime<ID, E>> = IdentityHashMap()
    private val rowsById: MutableMap<ID, E> = linkedMapOf()
    private val dirtyRows: DirtyRowQueue<ID> = DirtyRowQueue()
    private var scanCursor: ID? = null
    private val metricTags: MetricTags = MetricTags.of("collection" to collectionName)

    init {
        metrics.gauge("asteria.persistence.mongodb.scanned_table.loaded.rows", metricTags) {
            rowsById.size.toDouble()
        }
        metrics.gauge("asteria.persistence.mongodb.scanned_table.dirty.rows", metricTags) {
            dirtyRows.size.toDouble()
        }
    }

    override suspend fun loadRow(key: ID): E? {
        return collection.find(eq("_id", key)).firstOrNull()?.also(::attach)
    }

    override suspend fun loadAllRows(): Iterable<E> {
        return collection.find(Document()).toList().onEach(::attach)
    }

    override fun keyOf(row: E): ID = row.id

    override suspend fun flushRow(row: E): Boolean {
        if (runtime(row).scan(row).dirty) {
            dirtyRows.markDirty(row.id)
        }
        val flushed = runtime(row).flushSafely()
        if (flushed) {
            dirtyRows.markClean(row.id)
        }
        return flushed
    }

    override fun bindLease(row: E, lease: DataLease) {
        runtime(row).bindLease(lease)
        if (row is DataLeaseAware) {
            row.bindLease(lease)
        }
    }

    override fun afterUnload(row: E) {
        runtimes.remove(row)
        rowsById.remove(row.id)
        dirtyRows.remove(row.id)
        if (scanCursor == row.id) {
            scanCursor = null
        }
    }

    fun scanLoaded(): Int {
        var dirty = 0
        rowsById.values.forEach { row ->
            if (runtime(row).scan(row).dirty) {
                dirtyRows.markDirty(row.id)
                dirty += 1
            }
        }
        return dirty
    }

    fun scanSome(budget: MongoFlushBudget): MongoScanProgress {
        val ids = rowsById.keys.toList()
        if (ids.isEmpty()) return MongoScanProgress(0, 0, 0)

        val start = TimeSource.Monotonic.markNow()
        val firstIndex = nextScanIndex(ids)
        var index = firstIndex
        var scannedRows = 0
        var dirty = 0
        var changedFields = 0
        while (scannedRows < budget.maxRows && start.elapsedNow() < budget.maxDuration) {
            val id = ids[index]
            rowsById[id]?.let { row ->
                val result = runtime(row).scan(row)
                changedFields += result.changedFields
                if (result.dirty) {
                    dirtyRows.markDirty(row.id)
                    dirty += 1
                }
            }
            scanCursor = id
            scannedRows += 1
            index = (index + 1) % ids.size
            if (index == firstIndex) break
        }
        return MongoScanProgress(scannedRows, dirty, changedFields)
    }

    override suspend fun tick(policy: MongoScanFlushPolicy): MongoScanFlushProgress {
        return if (policy.scanBeforeFlush) {
            MongoScanFlushProgress(scanSome(policy.scanBudget), flushSome(policy.flushBudget))
        } else {
            val flush = flushSome(policy.flushBudget)
            MongoScanFlushProgress(scanSome(policy.scanBudget), flush)
        }
    }

    override suspend fun flushAllScanned(): Boolean {
        scanLoaded()
        return flush()
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
            if (runtime(row).flushSafely()) {
                flushedRows += 1
                dirtyRows.markClean(id)
            } else {
                failedRows += 1
                dirtyRows.markDirty(id)
            }
        }
        return MongoFlushProgress(attemptedRows, flushedRows, failedRows)
    }

    /**
     * Adds a new row to the loaded cache and enqueues all scanned fields as an upsert.
     *
     * This does not perform an insert-only existence check. Callers that need strict create semantics should query the
     * database or the owning business index before creating the row.
     */
    fun createLoaded(row: E): E {
        val key = row.id
        require(key !in rowsById && key !in loadedKeys()) { "scanned row $collectionName:$key is already loaded" }
        val runtime = MongoScannedDocumentRuntime(
            collectionName = collectionName,
            documentId = key,
            scanPlan = scanPlan,
            database = database,
            journal = journal,
            metrics = metrics,
            onDirty = { dirtyRows.markDirty(key) },
        )
        runtime.enqueueCreated(row)
        runtimes[row] = runtime
        rowsById[key] = row
        addLoaded(row)
        return row
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
        val runtime = MongoScannedDocumentRuntime(
            collectionName = collectionName,
            documentId = key,
            scanPlan = scanPlan,
            database = database,
            journal = journal,
            metrics = metrics,
        )
        runtime.enqueueDelete()
        return runtime.flushSafely()
    }

    suspend fun queryKeys(filter: Bson = Document()): List<ID> {
        return collection.find(filter).toList().map { it.id }
    }

    suspend fun querySnapshots(filter: Bson = Document()): List<E> {
        return collection.find(filter).toList()
    }

    protected fun runtime(row: E): MongoScannedDocumentRuntime<ID, E> {
        return requireNotNull(runtimes[row]) { "Mongo scan runtime for row ${row.id} is not loaded" }
    }

    private fun attach(entity: E) {
        if (entity.id in rowsById) return
        val runtime = MongoScannedDocumentRuntime(
            collectionName = collectionName,
            documentId = entity.id,
            scanPlan = scanPlan,
            database = database,
            journal = journal,
            metrics = metrics,
            onDirty = { dirtyRows.markDirty(entity.id) },
        )
        runtime.attachLoaded(entity)
        runtimes[entity] = runtime
        rowsById[entity.id] = entity
    }

    private fun nextScanIndex(ids: List<ID>): Int {
        val cursor = scanCursor ?: return 0
        val index = ids.indexOf(cursor)
        return if (index < 0) 0 else (index + 1) % ids.size
    }
}
