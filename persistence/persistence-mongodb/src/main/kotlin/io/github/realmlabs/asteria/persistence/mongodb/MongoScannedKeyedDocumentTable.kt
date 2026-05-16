package io.github.realmlabs.asteria.persistence.mongodb

import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Projections.include
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import io.github.realmlabs.asteria.persistence.*
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.bson.BsonDocument
import org.bson.Document
import org.bson.conversions.Bson
import java.util.*
import kotlin.reflect.KClass
import kotlin.time.Clock
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
    idType: KClass<ID>,
    private val scanPlan: EntityScanPlan<E>,
    cachePolicy: RowCachePolicy,
    private val database: MongoDatabase,
    private val journal: MongoWriteJournal = NoopMongoWriteJournal,
    private val metrics: Metrics = NoopMetrics,
    clock: Clock = Clock.System,
) : KeyedDataTable<ID, E>(cachePolicy, clock), MongoScannedTable {
    protected val collection: MongoCollection<E> = database.getCollection(collectionName, entityType.java)
    private val idProjectionCollection: MongoCollection<BsonDocument> =
        database.getCollection(collectionName, BsonDocument::class.java)
    private val idDecoder = MongoProjectedIdDecoder(collectionName, idType, database.codecRegistry)
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

    /**
     * Scans every loaded row and marks rows dirty when their hash snapshot changed.
     */
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

    /**
     * Scans loaded rows from a round-robin cursor until [budget] is exhausted.
     */
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

    override suspend fun drain(): Boolean {
        scanLoaded()
        return drainDirtyRows()
    }

    /**
     * Flushes a bounded number of rows already known to be dirty.
     *
     * Dirty knowledge comes from previous scans or create/delete operations. Failed rows are requeued; successful rows
     * are marked clean because their pending write queue has been acknowledged.
     */
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

    private suspend fun drainDirtyRows(): Boolean {
        var success = true
        val maxAttempts = dirtyRows.size
        repeat(maxAttempts) {
            val id = dirtyRows.next() ?: return@repeat
            val row = rowsById[id]
            if (row == null) {
                dirtyRows.markClean(id)
                return@repeat
            }
            if (runtime(row).flushSafely()) {
                dirtyRows.markClean(id)
            } else {
                dirtyRows.markDirty(id)
                success = false
            }
        }
        return success && dirtyRows.size == 0
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

    /**
     * Deletes a currently loaded row by enqueueing a document delete and flushing it immediately.
     *
     * The row is detached only after Mongo accepts the delete. A false result leaves the row loaded for retry.
     */
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

    /**
     * Runs a database-side query and returns only document ids.
     *
     * Use the returned keys with [use] before mutating rows so the scan runtime observes the current loaded row.
     */
    suspend fun queryKeys(filter: Bson = Document()): List<ID> {
        return idProjectionCollection.find(filter)
            .projection(include("_id"))
            .map(idDecoder::decode)
            .toList()
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
