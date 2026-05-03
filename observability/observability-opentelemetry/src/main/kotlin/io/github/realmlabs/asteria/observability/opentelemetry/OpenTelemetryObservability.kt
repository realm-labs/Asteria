package io.github.realmlabs.asteria.observability.opentelemetry

import io.github.realmlabs.asteria.observability.Observability
import io.opentelemetry.api.OpenTelemetry

fun OpenTelemetry.asAsteriaObservability(
    instrumentationName: String = "asteria",
): Observability {
    require(instrumentationName.isNotBlank()) { "instrumentation name must not be blank" }
    return Observability(
        tracer = OpenTelemetryTracer(getTracer(instrumentationName)),
        metrics = OpenTelemetryMetrics(getMeter(instrumentationName)),
    )
}
