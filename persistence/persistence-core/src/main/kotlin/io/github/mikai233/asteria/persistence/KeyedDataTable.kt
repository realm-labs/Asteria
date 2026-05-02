package io.github.mikai233.asteria.persistence

import java.time.Clock

/**
 * Actor-local row cache for a large logical table.
 *
 * A [DataManager] controls whether the owning module is loaded. [KeyedDataTable] controls the rows inside that module:
 * rows are loaded by key, accessed through [use], flushed before unload, and invalidated after unload.
 *
 * Database-side queries should return row keys or immutable snapshots. Mutating a row after a query should re-enter
 * this table with [use] and re-check the condition against the current row.
 */
abstract class KeyedDataTable<K : Any, R : Any>(
    private val cachePolicy: RowCachePolicy,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val rows: MutableMap<K, LoadedRow<K, R>> = linkedMapOf()

    fun loadedCount(): Int = rows.size

    fun loadedKeys(): Set<K> = rows.keys.toSet()

    /**
     * Loads [key] if needed and runs [block] while the row lease is active.
     */
    suspend fun <T> use(key: K, block: suspend (R) -> T): T {
        return useOrNull(key, block) ?: error("row $key not found")
    }

    /**
     * Same as [use], but returns null when the row does not exist in storage.
     */
    suspend fun <T> useOrNull(key: K, block: suspend (R) -> T): T? {
        val loaded = rows[key] ?: load(key) ?: return null
        touch(loaded)
        return try {
            block(loaded.row)
        } finally {
            touch(loaded)
        }
    }

    /**
     * Heavy operation that loads every row into this actor's memory.
     *
     * Prefer database-side key/snapshot queries when the caller only needs a filtered candidate set.
     */
    suspend fun loadAllIntoMemory(): Int {
        var loaded = 0
        loadAllRows().forEach { row ->
            val key = keyOf(row)
            if (rows[key] == null) {
                rows[key] = bindRow(key, row)
                loaded += 1
            }
        }
        return loaded
    }

    suspend fun flush(): Boolean {
        return rows.values.all { flushRow(it.row) }
    }

    /**
     * Flushes and unloads rows that have not been accessed for [RowCachePolicy.idleUnloadAfter].
     */
    suspend fun unloadIdle() {
        val now = clock.millis()
        val expired = rows.values
            .filter { now - it.lastAccessMillis >= cachePolicy.idleUnloadAfter.inWholeMilliseconds }
            .toList()
        expired.forEach { unload(it) }
    }

    protected abstract suspend fun loadRow(key: K): R?

    protected open suspend fun loadAllRows(): Iterable<R> {
        error("loadAllIntoMemory is not supported by ${this::class.qualifiedName}")
    }

    protected abstract fun keyOf(row: R): K

    protected open suspend fun flushRow(row: R): Boolean = true

    protected fun dropLoaded(key: K): R? {
        val loaded = rows.remove(key) ?: return null
        loaded.lease.invalidate()
        afterUnload(loaded.row)
        return loaded.row
    }

    protected open fun bindLease(row: R, lease: DataLease) {
        require(row is DataLeaseAware) { "row must implement DataLeaseAware" }
        row.bindLease(lease)
    }

    protected open fun afterUnload(row: R) = Unit

    private fun bindRow(key: K, row: R): LoadedRow<K, R> {
        val lease = DataLease("row $key")
        bindLease(row, lease)
        return LoadedRow(key, row, clock.millis(), lease)
    }

    private suspend fun load(key: K): LoadedRow<K, R>? {
        val row = loadRow(key) ?: return null
        val loaded = bindRow(key, row)
        rows[key] = loaded
        return loaded
    }

    private fun touch(row: LoadedRow<K, R>) {
        row.lease.ensureActive()
        row.lastAccessMillis = clock.millis()
    }

    private suspend fun unload(row: LoadedRow<K, R>) {
        if (!flushRow(row.row)) {
            row.lastAccessMillis = clock.millis()
            return
        }
        row.lease.invalidate()
        rows.remove(row.key)
        afterUnload(row.row)
    }
}

private data class LoadedRow<K : Any, R : Any>(
    val key: K,
    val row: R,
    var lastAccessMillis: Long,
    val lease: DataLease,
)
