package io.github.realmlabs.asteria.patch

import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext
import io.github.realmlabs.asteria.observability.metricsOrNoop
import io.github.realmlabs.asteria.observability.tracerOrNoop

class PatchModule private constructor(
    private val options: PatchModuleOptions,
) : AsteriaModule {
    override val name: String = "patch"

    override suspend fun install(context: ModuleContext) {
        val runtime = PatchRuntime(context.tracerOrNoop(), context.metricsOrNoop())
        val repository = options.repository ?: InMemoryRuntimePatchRepository()
        val resolver = options.resolver ?: StaticRuntimePatchPluginResolver()
        val service =
            PatchApplicationService(
                runtime,
                options.environment,
                repository,
                resolver,
                context.tracerOrNoop(),
                context.metricsOrNoop(),
            )
        val nodeResults = options.nodeResults ?: InMemoryRuntimePatchNodeResultRepository()
        val nodeProvider = options.nodeProvider ?: LocalPatchNodeProvider(options.environment)
        val nodeClient = options.nodeClient ?: LocalPatchNodeClient(service)
        val clusterService = PatchClusterApplicationService(repository, nodeProvider, nodeClient, nodeResults)

        context.services.register(PatchRuntime::class, runtime)
        context.services.register(RuntimePatchRepository::class, repository)
        context.services.register(RuntimePatchPluginResolver::class, resolver)
        context.services.register(PatchApplicationService::class, service)
        context.services.register(RuntimePatchNodeResultRepository::class, nodeResults)
        context.services.register(PatchClusterApplicationService::class, clusterService)
    }

    override suspend fun start(context: ModuleContext) {
        val service = context.services.get(PatchApplicationService::class)
        if (options.expireIncompatibleOnStart) {
            service.expireIncompatiblePatches()
        }
        if (options.applyOnStart) {
            service.reconcileEnabledPatches()
        }
    }

    companion object {
        operator fun invoke(environment: PatchEnvironment): PatchModule {
            return PatchModule(
                PatchModuleOptions(
                    environment = environment,
                    repository = null,
                    resolver = null,
                    nodeResults = null,
                    nodeProvider = null,
                    nodeClient = null,
                    applyOnStart = true,
                    expireIncompatibleOnStart = true,
                ),
            )
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
    val nodeResults: RuntimePatchNodeResultRepository?,
    val nodeProvider: PatchNodeProvider?,
    val nodeClient: PatchNodeClient?,
    val applyOnStart: Boolean,
    val expireIncompatibleOnStart: Boolean,
)

class PatchModuleBuilder {
    var environment: PatchEnvironment? = null
    private var repository: RuntimePatchRepository? = null
    private var resolver: RuntimePatchPluginResolver? = null
    private var nodeResults: RuntimePatchNodeResultRepository? = null
    private var nodeProvider: PatchNodeProvider? = null
    private var nodeClient: PatchNodeClient? = null
    var applyOnStart: Boolean = true
    var expireIncompatibleOnStart: Boolean = true

    fun repository(repository: RuntimePatchRepository) {
        this.repository = repository
    }

    fun resolver(resolver: RuntimePatchPluginResolver) {
        this.resolver = resolver
    }

    fun nodeResults(repository: RuntimePatchNodeResultRepository) {
        nodeResults = repository
    }

    fun nodeProvider(provider: PatchNodeProvider) {
        nodeProvider = provider
    }

    fun nodeClient(client: PatchNodeClient) {
        nodeClient = client
    }

    internal fun build(): PatchModuleOptions {
        return PatchModuleOptions(
            environment = requireNotNull(environment) { "patch environment must be configured" },
            repository = repository,
            resolver = resolver,
            nodeResults = nodeResults,
            nodeProvider = nodeProvider,
            nodeClient = nodeClient,
            applyOnStart = applyOnStart,
            expireIncompatibleOnStart = expireIncompatibleOnStart,
        )
    }
}
