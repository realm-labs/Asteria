package io.github.realmlabs.asteria.observability

data class Observability(
    val tracer: Tracer = NoopTracer,
    val metrics: Metrics = NoopMetrics,
)
