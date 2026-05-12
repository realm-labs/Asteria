package io.github.realmlabs.asteria.observability.opentelemetry

import io.github.realmlabs.asteria.observability.Observability
import io.opentelemetry.api.OpenTelemetry

/**
 * Adapts an OpenTelemetry SDK/API instance to Asteria's observability facade.
 *
 * [instrumentationName] is passed to both `getTracer` and `getMeter`, so deployments can distinguish framework metrics
 * from application-specific instruments in the backend.
 */
fun OpenTelemetry.asAsteriaObservability(
    instrumentationName: String = "asteria",
): Observability {
    require(instrumentationName.isNotBlank()) { "instrumentation name must not be blank" }
    return Observability(
        tracer = OpenTelemetryTracer(getTracer(instrumentationName)),
        metrics = OpenTelemetryMetrics(getMeter(instrumentationName)),
    )
}
