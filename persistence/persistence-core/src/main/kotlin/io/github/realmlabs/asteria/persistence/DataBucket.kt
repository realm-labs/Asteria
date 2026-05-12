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
    /**
     * Stable bucket name used in diagnostics and metrics.
     */
    val name: String,
    /**
     * Controls when [DataManager] creates and loads modules in this bucket.
     */
    val loadPolicy: DataLoadPolicy,
    /**
     * Idle timeout for [DataLoadPolicy.UnloadableLazy] modules.
     *
     * Null means the module is not eligible for idle unload. Non-unloadable policies reject this value because their
     * references may be handed out without a lease boundary.
     */
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
        /**
         * Use for small or required data that should be loaded before actor message handling starts.
         */
        fun eager(name: String = "eager"): DataBucket {
            return DataBucket(name, DataLoadPolicy.Eager)
        }

        /**
         * Use for optional data whose references may safely remain valid for the actor lifetime.
         */
        fun lazy(name: String = "lazy"): DataBucket {
            return DataBucket(name, DataLoadPolicy.Lazy)
        }

        /**
         * Use for large data that should be loaded only inside scoped [DataManager.use] blocks and unloaded when idle.
         */
        fun unloadableLazy(name: String, idleUnloadAfter: Duration): DataBucket {
            return DataBucket(name, DataLoadPolicy.UnloadableLazy, idleUnloadAfter)
        }
    }
}
