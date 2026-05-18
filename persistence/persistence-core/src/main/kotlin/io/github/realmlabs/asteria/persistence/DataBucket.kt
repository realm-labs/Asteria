package io.github.realmlabs.asteria.persistence

import kotlin.time.Duration

/**
 * Compile-time bucket policy marker.
 */
sealed interface DataBucketPolicy

/**
 * Policy marker for data whose references may remain valid for the actor lifetime.
 */
sealed interface ResidentDataBucketPolicy : DataBucketPolicy

/**
 * Policy marker for resident data loaded before actor message handling starts.
 */
sealed interface EagerDataBucketPolicy : ResidentDataBucketPolicy

/**
 * Policy marker for resident data loaded on first access.
 */
sealed interface LazyDataBucketPolicy : ResidentDataBucketPolicy

/**
 * Policy marker for data that must only be accessed inside a manager-controlled scope.
 */
sealed interface UnloadableDataBucketPolicy : DataBucketPolicy

/**
 * Grouping and lifecycle policy for a data module.
 */
sealed class DataBucket<out P : DataBucketPolicy>(
    /**
     * Stable bucket name used in diagnostics and metrics.
     */
    val name: String,
    internal val metricName: String,
) {
    init {
        require(name.isNotBlank()) { "data bucket name must not be blank" }
    }

    companion object {
        /**
         * Use for small or required data that should be loaded before actor message handling starts.
         */
        fun eager(name: String = "eager"): EagerDataBucket {
            return EagerDataBucket(name)
        }

        /**
         * Use for optional data whose references may safely remain valid for the actor lifetime.
         */
        fun lazy(name: String = "lazy"): LazyDataBucket {
            return LazyDataBucket(name)
        }

        /**
         * Use for large data that should be loaded only inside scoped [DataManager.use] blocks and unloaded when idle.
         */
        fun unloadableLazy(name: String, idleUnloadAfter: Duration): UnloadableLazyDataBucket {
            return UnloadableLazyDataBucket(name, idleUnloadAfter)
        }
    }
}

sealed class ResidentDataBucket<out P : ResidentDataBucketPolicy>(
    name: String,
    metricName: String,
) : DataBucket<P>(name, metricName)

class EagerDataBucket internal constructor(
    name: String,
) : ResidentDataBucket<EagerDataBucketPolicy>(name, "Eager")

class LazyDataBucket internal constructor(
    name: String,
) : ResidentDataBucket<LazyDataBucketPolicy>(name, "Lazy")

class UnloadableLazyDataBucket internal constructor(
    name: String,
    val idleUnloadAfter: Duration,
) : DataBucket<UnloadableDataBucketPolicy>(name, "UnloadableLazy") {
    init {
        require(idleUnloadAfter.isPositive()) { "data bucket idleUnloadAfter must be positive" }
    }
}
