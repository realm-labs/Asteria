package io.github.mikai233.asteria.persistence.mongodb

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
