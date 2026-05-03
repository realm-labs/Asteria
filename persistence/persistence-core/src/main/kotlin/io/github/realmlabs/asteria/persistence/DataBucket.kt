package io.github.realmlabs.asteria.persistence

import kotlin.time.Duration

/**
 * Loading policy for one actor-local data unit.
 */
enum class DataLoadPolicy {
    /**
     * Loaded when the actor data manager starts.
     */
    Eager,

    /**
     * Loaded on first access and kept in memory until the actor stops.
     */
    Lazy,

    /**
     * Loaded on first scoped access and eligible for idle unload.
     */
    UnloadableLazy,
}

/**
 * Grouping and lifecycle policy for a data module.
 */
data class DataBucket(
    val name: String,
    val loadPolicy: DataLoadPolicy,
    val idleUnloadAfter: Duration? = null,
) {
    init {
        require(name.isNotBlank()) { "data bucket name must not be blank" }
        require(loadPolicy == DataLoadPolicy.UnloadableLazy || idleUnloadAfter == null) {
            "idleUnloadAfter only applies to unloadable lazy buckets"
        }
    }

    val unloadable: Boolean
        get() = loadPolicy == DataLoadPolicy.UnloadableLazy && idleUnloadAfter != null

    companion object {
        fun eager(name: String = "eager"): DataBucket {
            return DataBucket(name, DataLoadPolicy.Eager)
        }

        fun lazy(name: String = "lazy"): DataBucket {
            return DataBucket(name, DataLoadPolicy.Lazy)
        }

        fun unloadableLazy(name: String, idleUnloadAfter: Duration): DataBucket {
            return DataBucket(name, DataLoadPolicy.UnloadableLazy, idleUnloadAfter)
        }
    }
}
