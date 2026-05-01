package io.github.mikai233.asteria.observability.opentelemetry

import io.github.mikai233.asteria.observability.Observability
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
