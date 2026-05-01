package io.github.mikai233.asteria.script.job

import io.github.mikai233.asteria.core.AsteriaDsl
import io.github.mikai233.asteria.core.AsteriaModule
import io.github.mikai233.asteria.core.ModuleContext
import io.github.mikai233.asteria.observability.Metrics
import io.github.mikai233.asteria.observability.NoopMetrics
import io.github.mikai233.asteria.observability.NoopTracer
import io.github.mikai233.asteria.observability.Tracer
import io.github.mikai233.asteria.script.ScriptRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class ScriptJobModule private constructor(
    private val options: ScriptJobModuleOptions,
) : AsteriaModule {
    override val name: String = "script-job"

    private var scope: CoroutineScope? = null

    override suspend fun install(context: ModuleContext) {
        val repository = options.repository ?: InMemoryScriptJobRepository()
        context.services.register(ScriptJobRepository::class, repository)
        context.services.register(ScriptJobModuleOptions::class, options)
    }

    override suspend fun start(context: ModuleContext) {
        val jobScope = CoroutineScope(SupervisorJob())
        val service = ScriptJobService(
            runtime = context.services.get<ScriptRuntime>(),
            repository = context.services.get(),
            scope = jobScope,
            tracer = context.services.find<Tracer>() ?: NoopTracer,
            metrics = context.services.find<Metrics>() ?: NoopMetrics,
        )
        scope = jobScope
        context.services.register(ScriptJobService::class, service)
    }

    override suspend fun stop(context: ModuleContext) {
        scope?.cancel()
        scope = null
    }

    companion object {
        operator fun invoke(configure: ScriptJobModuleBuilder.() -> Unit = {}): ScriptJobModule {
            return ScriptJobModule(ScriptJobModuleBuilder().apply(configure).build())
        }
    }
}

data class ScriptJobModuleOptions(
    val repository: ScriptJobRepository?,
)

@AsteriaDsl
class ScriptJobModuleBuilder {
    private var repository: ScriptJobRepository? = null

    fun repository(repository: ScriptJobRepository) {
        this.repository = repository
    }

    internal fun build(): ScriptJobModuleOptions {
        return ScriptJobModuleOptions(repository)
    }
}
