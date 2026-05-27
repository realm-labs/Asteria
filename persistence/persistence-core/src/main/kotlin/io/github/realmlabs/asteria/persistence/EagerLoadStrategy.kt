package io.github.realmlabs.asteria.persistence

/**
 * Startup loading strategy for [DataBucket.eager] modules.
 */
sealed class EagerLoadStrategy {
    /**
     * Loads eager modules one by one in registration order.
     */
    data object Sequential : EagerLoadStrategy()

    /**
     * Loads eager modules concurrently.
     *
     * Use only when eager modules have no load-time ordering dependency on each other and their storage dependencies are
     * safe for concurrent access. If one module fails to load, startup fails, remaining load jobs are cancelled, and the
     * manager installs none of the loaded eager data.
     */
    class Parallel(
        val maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
    ) : EagerLoadStrategy() {
        init {
            require(maxConcurrency > 0) { "maxConcurrency must be positive" }
        }

        companion object {
            /**
             * Conservative default to avoid overwhelming storage pools during actor startup bursts.
             */
            const val DEFAULT_MAX_CONCURRENCY: Int = 4
        }
    }
}
