package io.github.realmlabs.asteria.observability

/**
 * Minimal metrics facade used by framework modules.
 *
 * Implementations should be safe to call from actor threads, coroutine dispatchers, and network event loops. Metric
 * names must be non-blank; implementations may cache instruments by name and apply [MetricTags] at record time.
 */
interface Metrics {
    fun counter(name: String, tags: MetricTags = MetricTags.Empty): Counter

    fun timer(name: String, tags: MetricTags = MetricTags.Empty): Timer

    fun gauge(name: String, tags: MetricTags = MetricTags.Empty, value: () -> Double)
}

/**
 * Immutable metric tag set.
 *
 * Tag keys must be non-blank. Combining tag sets with [plus] lets call-site tags override shared tags with the same
 * key.
 */
data class MetricTags(
    private val values: Map<String, String> = emptyMap(),
) {
    init {
        values.forEach { (key, _) -> require(key.isNotBlank()) { "metric tag key must not be blank" } }
    }

    fun asMap(): Map<String, String> = values

    operator fun plus(other: MetricTags): MetricTags {
        return MetricTags(values + other.values)
    }

    companion object {
        val Empty = MetricTags()

        fun of(vararg pairs: Pair<String, String>): MetricTags {
            return MetricTags(mapOf(*pairs))
        }
    }
}

/**
 * Monotonic counter instrument.
 */
interface Counter {
    fun increment(amount: Long = 1)
}

/**
 * Duration recorder using milliseconds as the common unit across implementations.
 */
interface Timer {
    suspend fun <T> record(block: suspend () -> T): T

    fun record(durationMillis: Long)
}

/**
 * Metrics implementation used when no backend has been installed.
 */
object NoopMetrics : Metrics {
    override fun counter(name: String, tags: MetricTags): Counter {
        require(name.isNotBlank()) { "counter name must not be blank" }
        return NoopCounter
    }

    override fun timer(name: String, tags: MetricTags): Timer {
        require(name.isNotBlank()) { "timer name must not be blank" }
        return NoopTimer
    }

    override fun gauge(name: String, tags: MetricTags, value: () -> Double) {
        require(name.isNotBlank()) { "gauge name must not be blank" }
    }
}

object NoopCounter : Counter {
    override fun increment(amount: Long) {
        require(amount >= 0) { "counter increment amount must not be negative" }
    }
}

object NoopTimer : Timer {
    override suspend fun <T> record(block: suspend () -> T): T {
        return block()
    }

    override fun record(durationMillis: Long) {
        require(durationMillis >= 0) { "timer duration must not be negative" }
    }
}
