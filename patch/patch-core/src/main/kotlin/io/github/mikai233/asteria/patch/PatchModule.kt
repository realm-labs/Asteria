package io.github.mikai233.asteria.patch

import io.github.mikai233.asteria.core.AsteriaModule
import io.github.mikai233.asteria.core.ModuleContext

class PatchModule private constructor(
    private val options: PatchModuleOptions,
) : AsteriaModule {
    override val name: String = "patch"

    override suspend fun install(context: ModuleContext) {
        val runtime = PatchRuntime(options.environment)
        val repository = options.repository ?: InMemoryRuntimePatchRepository()
        val resolver = options.resolver ?: StaticRuntimePatchPluginResolver()
        val service = PatchApplicationService(runtime, repository, resolver)

        context.services.register(PatchRuntime::class, runtime)
        context.services.register(RuntimePatchRepository::class, repository)
        context.services.register(RuntimePatchPluginResolver::class, resolver)
        context.services.register(PatchApplicationService::class, service)
    }

    override suspend fun start(context: ModuleContext) {
        if (options.applyOnStart) {
            context.services.get(PatchApplicationService::class).applyEnabledPatches()
        }
    }

    companion object {
        operator fun invoke(environment: PatchEnvironment): PatchModule {
            return PatchModule(PatchModuleOptions(environment, null, null, applyOnStart = true))
        }

        operator fun invoke(configure: PatchModuleBuilder.() -> Unit): PatchModule {
            return PatchModule(PatchModuleBuilder().apply(configure).build())
        }
    }
}

data class PatchModuleOptions(
    val environment: PatchEnvironment,
    val repository: RuntimePatchRepository?,
    val resolver: RuntimePatchPluginResolver?,
    val applyOnStart: Boolean,
)

class PatchModuleBuilder {
    var environment: PatchEnvironment? = null
    private var repository: RuntimePatchRepository? = null
    private var resolver: RuntimePatchPluginResolver? = null
    var applyOnStart: Boolean = true

    fun repository(repository: RuntimePatchRepository) {
        this.repository = repository
    }

    fun resolver(resolver: RuntimePatchPluginResolver) {
        this.resolver = resolver
    }

    internal fun build(): PatchModuleOptions {
        return PatchModuleOptions(
            environment = requireNotNull(environment) { "patch environment must be configured" },
            repository = repository,
            resolver = resolver,
            applyOnStart = applyOnStart,
        )
    }
}
