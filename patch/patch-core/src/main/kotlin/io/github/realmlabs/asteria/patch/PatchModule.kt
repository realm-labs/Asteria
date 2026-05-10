package io.github.realmlabs.asteria.patch

import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext
import io.github.realmlabs.asteria.observability.metricsOrNoop
import io.github.realmlabs.asteria.observability.tracerOrNoop
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class PatchModule private constructor(
    private val options: PatchModuleOptions,
) : AsteriaModule {
    override val name: String = "patch"

    private val logger = LoggerFactory.getLogger(PatchModule::class.java)
    private var scope: CoroutineScope? = null
    private var reconcileJob: Job? = null
    private var installed: Boolean = false

    override suspend fun install(context: ModuleContext) {
        if (options.environment != null) {
            installServices(context, options.environment)
        }
    }

    override suspend fun start(context: ModuleContext) {
        val service = installServices(context, environment(context))
        if (options.expireIncompatibleOnStart) {
            service.expireIncompatiblePatches()
        }
        if (options.applyOnStart) {
            service.reconcileEnabledPatches()
        }
        startPeriodicReconcile(service)
    }

    override suspend fun stop(context: ModuleContext) {
        reconcileJob?.cancelAndJoin()
        reconcileJob = null
        scope?.cancel()
        scope = null
        installed = false
    }

    private suspend fun installServices(
        context: ModuleContext,
        environment: PatchEnvironment,
    ): PatchApplicationService {
        if (installed) {
            return context.services.get(PatchApplicationService::class)
        }
        val runtime = PatchRuntime(context.runtime, context.tracerOrNoop(), context.metricsOrNoop())
        val repository = options.repository ?: InMemoryRuntimePatchRepository()
        val resolver = options.resolver ?: StaticRuntimePatchPluginResolver()
        val service =
            PatchApplicationService(
                runtime,
                environment,
                repository,
                resolver,
                context.tracerOrNoop(),
                context.metricsOrNoop(),
            )
        val nodeResults = options.nodeResults ?: InMemoryRuntimePatchNodeResultRepository()
        val nodeProvider = options.nodeProvider ?: LocalPatchNodeProvider(environment)
        val nodeClient = options.nodeClient ?: LocalPatchNodeClient(service)
        val clusterService = PatchClusterApplicationService(repository, nodeProvider, nodeClient, nodeResults)

        context.services.register(PatchRuntime::class, runtime)
        context.services.register(RuntimePatchRepository::class, repository)
        context.services.register(RuntimePatchPluginResolver::class, resolver)
        context.services.register(PatchApplicationService::class, service)
        context.services.register(RuntimePatchNodeResultRepository::class, nodeResults)
        context.services.register(PatchClusterApplicationService::class, clusterService)
        installed = true
        return service
    }

    private suspend fun environment(context: ModuleContext): PatchEnvironment {
        options.environment?.let { return it }
        options.environmentProvider?.let { return it.environment(context) }
        val version = requireNotNull(options.version) {
            "patch version must be configured when patch environment is inferred"
        }
        return PatchEnvironment(
            appName = context.name,
            version = version,
            roles = context.roles,
        )
    }

    private fun startPeriodicReconcile(service: PatchApplicationService) {
        val interval = options.reconcileInterval ?: return
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob()).also { scope ->
            reconcileJob = scope.launch {
                while (isActive) {
                    delay(interval)
                    try {
                        val report = service.reconcileEnabledPatches()
                        logger.debug(
                            "periodic patch reconcile completed applied={} removed={}",
                            report.appliedCount,
                            report.removedCount,
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        logger.warn("periodic patch reconcile failed; retrying after {}", interval, error)
                    }
                }
            }
        }
    }

    companion object {
        operator fun invoke(environment: PatchEnvironment): PatchModule {
            return PatchModule(
                PatchModuleOptions(
                    environment = environment,
                    version = null,
                    environmentProvider = null,
                    repository = null,
                    resolver = null,
                    nodeResults = null,
                    nodeProvider = null,
                    nodeClient = null,
                    applyOnStart = true,
                    expireIncompatibleOnStart = true,
                    reconcileInterval = DEFAULT_RECONCILE_INTERVAL,
                ),
            )
        }

        operator fun invoke(configure: PatchModuleBuilder.() -> Unit): PatchModule {
            return PatchModule(PatchModuleBuilder().apply(configure).build())
        }
    }
}

data class PatchModuleOptions(
    val environment: PatchEnvironment?,
    val version: String?,
    val environmentProvider: PatchEnvironmentProvider?,
    val repository: RuntimePatchRepository?,
    val resolver: RuntimePatchPluginResolver?,
    val nodeResults: RuntimePatchNodeResultRepository?,
    val nodeProvider: PatchNodeProvider?,
    val nodeClient: PatchNodeClient?,
    val applyOnStart: Boolean,
    val expireIncompatibleOnStart: Boolean,
    val reconcileInterval: Duration?,
)

fun interface PatchEnvironmentProvider {
    suspend fun environment(context: ModuleContext): PatchEnvironment
}

class PatchModuleBuilder {
    var environment: PatchEnvironment? = null
    var version: String? = null
    private var environmentProvider: PatchEnvironmentProvider? = null
    private var repository: RuntimePatchRepository? = null
    private var resolver: RuntimePatchPluginResolver? = null
    private var nodeResults: RuntimePatchNodeResultRepository? = null
    private var nodeProvider: PatchNodeProvider? = null
    private var nodeClient: PatchNodeClient? = null
    var applyOnStart: Boolean = true
    var expireIncompatibleOnStart: Boolean = true
    var reconcileInterval: Duration? = DEFAULT_RECONCILE_INTERVAL

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

    fun environment(provider: PatchEnvironmentProvider) {
        environmentProvider = provider
    }

    internal fun build(): PatchModuleOptions {
        reconcileInterval?.let {
            require(it > Duration.ZERO) { "patch reconcile interval must be positive" }
        }
        version?.let { require(it.isNotBlank()) { "patch version must not be blank" } }
        return PatchModuleOptions(
            environment = environment,
            version = version,
            environmentProvider = environmentProvider,
            repository = repository,
            resolver = resolver,
            nodeResults = nodeResults,
            nodeProvider = nodeProvider,
            nodeClient = nodeClient,
            applyOnStart = applyOnStart,
            expireIncompatibleOnStart = expireIncompatibleOnStart,
            reconcileInterval = reconcileInterval,
        ).also {
            require(
                it.environment != null || it.environmentProvider != null || it.version != null
            ) { "patch environment, environment provider, or version must be configured" }
        }
    }
}

private val DEFAULT_RECONCILE_INTERVAL: Duration = 1.minutes
