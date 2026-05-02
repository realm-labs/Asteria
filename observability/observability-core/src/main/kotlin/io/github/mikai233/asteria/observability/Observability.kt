package io.github.mikai233.asteria.observability

data class Observability(
    val tracer: Tracer = NoopTracer,
    val metrics: Metrics = NoopMetrics,
)
