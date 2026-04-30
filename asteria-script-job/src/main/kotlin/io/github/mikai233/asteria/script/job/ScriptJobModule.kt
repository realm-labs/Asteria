package io.github.mikai233.asteria.script.job

import io.github.mikai233.asteria.core.AsteriaDsl
import io.github.mikai233.asteria.core.AsteriaModule
import io.github.mikai233.asteria.core.ModuleContext
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
        val store = options.store ?: InMemoryScriptJobStore()
        context.services.register(ScriptJobStore::class, store)
        context.services.register(ScriptJobModuleOptions::class, options)
    }

    override suspend fun start(context: ModuleContext) {
        val jobScope = CoroutineScope(SupervisorJob())
        val service = ScriptJobService(
            runtime = context.services.get<ScriptRuntime>(),
            store = context.services.get(),
            scope = jobScope,
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
    val store: ScriptJobStore?,
)

@AsteriaDsl
class ScriptJobModuleBuilder {
    private var store: ScriptJobStore? = null

    fun store(store: ScriptJobStore) {
        this.store = store
    }

    internal fun build(): ScriptJobModuleOptions {
        return ScriptJobModuleOptions(store)
    }
}
