package io.github.mikai233.asteria.observability.opentelemetry

import io.github.mikai233.asteria.observability.Counter
import io.github.mikai233.asteria.observability.MetricTags
import io.github.mikai233.asteria.observability.Metrics
import io.github.mikai233.asteria.observability.Timer
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.metrics.ObservableDoubleGauge
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.TimeSource

class OpenTelemetryMetrics(
    private val meter: Meter,
) : Metrics {
    private val counters: MutableMap<String, LongCounter> = ConcurrentHashMap()
    private val timers: MutableMap<String, DoubleHistogram> = ConcurrentHashMap()
    private val gauges: MutableList<ObservableDoubleGauge> = mutableListOf()

    override fun counter(name: String, tags: MetricTags): Counter {
        require(name.isNotBlank()) { "counter name must not be blank" }
        return OpenTelemetryCounter(
            counter = counters.computeIfAbsent(name) { meter.counterBuilder(it).build() },
            attributes = tags.toOpenTelemetryAttributes(),
        )
    }

    override fun timer(name: String, tags: MetricTags): Timer {
        require(name.isNotBlank()) { "timer name must not be blank" }
        return OpenTelemetryTimer(
            histogram = timers.computeIfAbsent(name) { meter.histogramBuilder(it).setUnit("ms").build() },
            attributes = tags.toOpenTelemetryAttributes(),
        )
    }

    override fun gauge(name: String, tags: MetricTags, value: () -> Double) {
        require(name.isNotBlank()) { "gauge name must not be blank" }
        val attributes = tags.toOpenTelemetryAttributes()
        gauges += meter.gaugeBuilder(name).buildWithCallback {
            it.record(value(), attributes)
        }
    }
}

private class OpenTelemetryCounter(
    private val counter: LongCounter,
    private val attributes: io.opentelemetry.api.common.Attributes,
) : Counter {
    override fun increment(amount: Long) {
        require(amount >= 0) { "counter increment amount must not be negative" }
        counter.add(amount, attributes)
    }
}

private class OpenTelemetryTimer(
    private val histogram: DoubleHistogram,
    private val attributes: io.opentelemetry.api.common.Attributes,
) : Timer {
    override suspend fun <T> record(block: suspend () -> T): T {
        val startedAt = TimeSource.Monotonic.markNow()
        try {
            return block()
        } finally {
            record(startedAt.elapsedNow().inWholeMilliseconds)
        }
    }

    override fun record(durationMillis: Long) {
        require(durationMillis >= 0) { "timer duration must not be negative" }
        histogram.record(durationMillis.toDouble(), attributes)
    }
}
