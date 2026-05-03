package io.github.realmlabs.asteria.observability

interface Metrics {
    fun counter(name: String, tags: MetricTags = MetricTags.Empty): Counter

    fun timer(name: String, tags: MetricTags = MetricTags.Empty): Timer

    fun gauge(name: String, tags: MetricTags = MetricTags.Empty, value: () -> Double)
}

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

interface Counter {
    fun increment(amount: Long = 1)
}

interface Timer {
    suspend fun <T> record(block: suspend () -> T): T

    fun record(durationMillis: Long)
}

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
