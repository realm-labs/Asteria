package io.github.mikai233.asteria.observability

import io.github.mikai233.asteria.core.AsteriaDsl
import io.github.mikai233.asteria.core.AsteriaModule
import io.github.mikai233.asteria.core.ModuleContext

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

@AsteriaDsl
class ObservabilityModuleBuilder {
    var tracer: Tracer = NoopTracer
    var metrics: Metrics = NoopMetrics

    internal fun build(): Observability {
        return Observability(tracer, metrics)
    }
}
