package io.github.realmlabs.asteria.observability

import io.github.realmlabs.asteria.core.ModuleContext
import io.github.realmlabs.asteria.core.NodeRuntime

fun ModuleContext.tracerOrNoop(): Tracer {
    return services.find<Tracer>() ?: NoopTracer
}

fun ModuleContext.metricsOrNoop(): Metrics {
    return services.find<Metrics>() ?: NoopMetrics
}

fun NodeRuntime.tracerOrNoop(): Tracer {
    return services.find<Tracer>() ?: NoopTracer
}

fun NodeRuntime.metricsOrNoop(): Metrics {
    return services.find<Metrics>() ?: NoopMetrics
}
