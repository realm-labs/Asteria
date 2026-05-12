package io.github.realmlabs.asteria.observability

import io.github.realmlabs.asteria.core.AsteriaDsl
import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext

/**
 * Installs tracing and metrics services into the application registry.
 *
 * Install this module before modules that should observe startup work. Modules that are installed earlier still fall
 * back to no-op services through the `OrNoop` helpers.
 */
class ObservabilityModule private constructor(
    private val observability: Observability,
) : AsteriaModule {
    override val name: String = "observability"

    override suspend fun install(context: ModuleContext) {
        context.services.register(Observability::class, observability)
        context.services.register(Tracer::class, observability.tracer)
        context.services.register(Metrics::class, observability.metrics)
    }

    companion object {
        operator fun invoke(configure: ObservabilityModuleBuilder.() -> Unit = {}): ObservabilityModule {
            return ObservabilityModule(ObservabilityModuleBuilder().apply(configure).build())
        }
    }
}

/**
 * DSL backing [ObservabilityModule].
 */
@AsteriaDsl
class ObservabilityModuleBuilder {
    var tracer: Tracer = NoopTracer
    var metrics: Metrics = NoopMetrics

    internal fun build(): Observability {
        return Observability(tracer, metrics)
    }
}
