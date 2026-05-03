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
    val attemptedRows: Int,
    val flushedRows: Int,
    val failedRows: Int,
)

/**
 * Policy for spreading scan and flush work across ticks.
 */
data class MongoScanFlushPolicy(
    val scanBudget: MongoFlushBudget,
    val flushBudget: MongoFlushBudget,
    val scanBeforeFlush: Boolean = true,
)

data class MongoScanProgress(
    val scannedRows: Int,
    val dirtyRows: Int,
    val changedFields: Int,
)

data class MongoScanFlushProgress(
    val scan: MongoScanProgress,
    val flush: MongoFlushProgress,
)
