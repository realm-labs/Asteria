package io.github.realmlabs.asteria.persistence.mongodb

import kotlin.time.Duration

/**
 * Budget for spreading row flushes across ticks.
 */
data class MongoFlushBudget(
    val maxRows: Int,
    val maxDuration: Duration,
) {
    init {
        require(maxRows > 0) { "flush maxRows must be positive" }
        require(maxDuration.isPositive()) { "flush maxDuration must be positive" }
    }
}

data class MongoFlushProgress(
    /**
     * Rows selected from the dirty queue and attempted during this pass.
     */
    val attemptedRows: Int,
    /**
     * Attempted rows whose pending Mongo writes were accepted by the driver.
     */
    val flushedRows: Int,
    /**
     * Attempted rows requeued for a later retry after a flush failure.
     */
    val failedRows: Int,
)

/**
 * Policy for spreading scan and flush work across ticks.
 */
data class MongoScanFlushPolicy(
    /**
     * Per-tick limit for hash scanning loaded rows.
     */
    val scanBudget: MongoFlushBudget,
    /**
     * Per-tick limit for flushing rows already known to be dirty.
     */
    val flushBudget: MongoFlushBudget,
    /**
     * When true, newly detected changes can be flushed in the same tick.
     */
    val scanBeforeFlush: Boolean = true,
)

data class MongoScanProgress(
    /**
     * Loaded rows compared against their previous scan snapshot.
     */
    val scannedRows: Int,
    /**
     * Scanned rows that produced at least one pending write.
     */
    val dirtyRows: Int,
    /**
     * Total field paths that changed across all scanned rows.
     */
    val changedFields: Int,
)

data class MongoScanFlushProgress(
    val scan: MongoScanProgress,
    val flush: MongoFlushProgress,
)
