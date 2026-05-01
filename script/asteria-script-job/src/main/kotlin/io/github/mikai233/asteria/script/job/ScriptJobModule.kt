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
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
        if (options.recoverOnStart) {
            jobScope.launch {
                service.resumeIncompleteJobs(
                    timeout = options.recoveryTimeout,
                    limit = options.recoveryLimit,
                )
            }
        }
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
    val recoverOnStart: Boolean,
    val recoveryLimit: Int,
    val recoveryTimeout: Duration,
)

@AsteriaDsl
class ScriptJobModuleBuilder {
    private var repository: ScriptJobRepository? = null
    private var recoverOnStart: Boolean = true
    private var recoveryLimit: Int = 100
    private var recoveryTimeout: Duration = 3.seconds

    fun repository(repository: ScriptJobRepository) {
        this.repository = repository
    }

    fun recoverOnStart(enabled: Boolean) {
        this.recoverOnStart = enabled
    }

    fun recoveryLimit(limit: Int) {
        this.recoveryLimit = limit
    }

    fun recoveryTimeout(timeout: Duration) {
        this.recoveryTimeout = timeout
    }

    internal fun build(): ScriptJobModuleOptions {
        require(recoveryLimit > 0) { "script job recovery limit must be positive" }
        require(recoveryTimeout > Duration.ZERO) { "script job recovery timeout must be positive" }
        return ScriptJobModuleOptions(
            repository = repository,
            recoverOnStart = recoverOnStart,
            recoveryLimit = recoveryLimit,
            recoveryTimeout = recoveryTimeout,
        )
    }
}
