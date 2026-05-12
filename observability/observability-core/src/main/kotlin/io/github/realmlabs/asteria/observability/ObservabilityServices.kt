package io.github.realmlabs.asteria.observability

import io.github.realmlabs.asteria.core.ModuleContext
import io.github.realmlabs.asteria.core.NodeRuntime

/**
 * Returns the registered tracer or [NoopTracer] when observability is optional for the caller.
 */
fun ModuleContext.tracerOrNoop(): Tracer {
    return services.find<Tracer>() ?: NoopTracer
}

/**
 * Returns the registered metrics facade or [NoopMetrics] when observability is optional for the caller.
 */
fun ModuleContext.metricsOrNoop(): Metrics {
    return services.find<Metrics>() ?: NoopMetrics
}

/**
 * Runtime-level variant of [ModuleContext.tracerOrNoop].
 */
fun NodeRuntime.tracerOrNoop(): Tracer {
    return services.find<Tracer>() ?: NoopTracer
}

/**
 * Runtime-level variant of [ModuleContext.metricsOrNoop].
 */
fun NodeRuntime.metricsOrNoop(): Metrics {
    return services.find<Metrics>() ?: NoopMetrics
}
