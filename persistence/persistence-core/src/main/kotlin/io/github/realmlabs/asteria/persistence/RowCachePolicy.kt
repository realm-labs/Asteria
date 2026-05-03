package io.github.realmlabs.asteria.persistence

import kotlin.time.Duration

/**
 * Row-level cache policy for data modules that manage many records.
 *
 * This policy is intentionally separate from [DataBucket]. [DataBucket] controls whether the containing data module is
 * loaded by the actor. [RowCachePolicy] controls how rows inside that module are loaded and unloaded.
 */
data class RowCachePolicy(
    val idleUnloadAfter: Duration,
) {
    init {
        require(idleUnloadAfter.isPositive()) { "row idleUnloadAfter must be positive" }
    }
}
