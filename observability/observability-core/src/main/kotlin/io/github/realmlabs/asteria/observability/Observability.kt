package io.github.realmlabs.asteria.observability

/**
 * Observability services installed into the application service registry.
 *
 * The defaults are no-op implementations so modules can safely request tracing and metrics before an integration such
 * as OpenTelemetry is installed.
 */
data class Observability(
    val tracer: Tracer = NoopTracer,
    val metrics: Metrics = NoopMetrics,
)
