package io.github.realmlabs.asteria.observability.opentelemetry

import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.TraceAttributes
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenTelemetryObservabilityTest {
    @Test
    fun tracerExportsSpanAttributesEventsAndContext() = runBlocking {
        val exporter = InMemorySpanExporter.create()
        val tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()
        val openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .build()
        val tracer = OpenTelemetryTracer(openTelemetry.getTracer("test"))

        val context = tracer.span("test.span", TraceAttributes.of("route" to "login")) {
            event("test.event", TraceAttributes.of("phase" to "started"))
            context
        }

        val span = exporter.finishedSpanItems.single()
        assertTrue(context.isValid)
        assertEquals("test.span", span.name)
        assertEquals("login", span.attributes.get(AttributeKey.stringKey("route")))
        assertEquals("test.event", span.events.single().name)
        assertEquals("started", span.events.single().attributes.get(AttributeKey.stringKey("phase")))
        assertEquals(StatusCode.UNSET, span.status.statusCode)

        tracerProvider.close()
    }

    @Test
    fun metricsExportCounterTimerAndGauge() {
        var gaugeValue = 7.0
        val reader = InMemoryMetricReader.create()
        val meterProvider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build()
        val metrics = OpenTelemetryMetrics(meterProvider.get("test"))
        val tags = MetricTags.of("kind" to "login")

        metrics.counter("asteria.test.counter", tags).increment(3)
        metrics.timer("asteria.test.timer", tags).record(12)
        metrics.gauge("asteria.test.gauge", tags) { gaugeValue }
        gaugeValue = 8.0

        val metricData = reader.collectAllMetrics().associateBy { it.name }

        assertEquals(3, metricData.getValue("asteria.test.counter").longSumData.points.single().value)
        metricData.getValue("asteria.test.timer").histogramData.points.single().also {
            assertEquals(1, it.count)
            assertEquals(12.0, it.sum)
        }
        assertEquals(8.0, metricData.getValue("asteria.test.gauge").doubleGaugeData.points.single().value)

        meterProvider.close()
    }
}
