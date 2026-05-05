package io.github.realmlabs.asteria.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * Drives Asteria modules against a concrete [NodeRuntime].
 *
 * This is the formal entry for projects that already have their own node type, such as `PlayerNode` or `WorldNode`.
 * Build an [AsteriaApplication] for the module/topology definition, then bind that definition to the external runtime
 * with [AsteriaApplication.bind].
 *
 * Lifecycle transitions are serialized by an internal mutex. This prevents overlapping launch and stop operations, but
 * it does not make arbitrary services or modules themselves thread-safe.
 */
class AsteriaModuleLifecycle(
    val runtime: NodeRuntime,
    val topology: RuntimeTopology = RuntimeTopology.Empty,
    modules: Iterable<AsteriaModule>,
    private val stateWriter: ((NodeState) -> Unit)? = null,
) {
    private val logger = LoggerFactory.getLogger(AsteriaModuleLifecycle::class.java)
    private val modules: List<AsteriaModule> = modules.toList()
    private val stateListeners: MutableMap<NodeState, MutableList<suspend () -> Unit>> = linkedMapOf()
    private val installedModuleIndexes: MutableSet<Int> = linkedSetOf()
    private val startedModuleIndexes: MutableSet<Int> = linkedSetOf()
    private val lifecycleLock = Mutex()

    @Volatile
    private var currentState: NodeState = NodeState.Unstarted

    val state: NodeState
        get() = currentState

    /**
     * Registers a callback for a future transition into [state].
     *
     * Listeners are not replayed for the current state. They run inline while the lifecycle mutex is held, so they
     * should avoid long-running work and should not try to recursively launch or stop the same lifecycle.
     */
    fun onState(state: NodeState, listener: suspend () -> Unit) {
        stateListeners.getOrPut(state) { mutableListOf() }.add(listener)
    }

    /**
     * Installs and starts modules in declaration order.
     *
     * Launch is valid only from [NodeState.Unstarted] or [NodeState.Stopped]. Startup failures are fatal: the original
     * error is propagated after best-effort rollback of modules that already installed or started.
     */
    suspend fun launch() {
        lifecycleLock.withLock {
            check(currentState == NodeState.Unstarted || currentState == NodeState.Stopped) {
                "runtime ${runtime.name} cannot launch from state $currentState"
            }
            val startedAt = System.nanoTime()
            logger.info(
                "launching runtime {} modules={} roles={} entities={} singletons={}",
                runtime.name,
                modules.size,
                topology.declaredRoles.size,
                topology.entities.size,
                topology.singletons.size,
            )
            val context = moduleContext()
            try {
                changeState(NodeState.Starting)
                runtime.services.register(AsteriaModuleLifecycle::class, this)
                modules.forEachIndexed { index, module ->
                    module.install(context)
                    installedModuleIndexes += index
                }
                modules.forEachIndexed { index, module ->
                    module.start(context)
                    startedModuleIndexes += index
                }
                changeState(NodeState.Started)
                logger.info(
                    "runtime {} launched in {} ms",
                    runtime.name,
                    (System.nanoTime() - startedAt) / 1_000_000,
                )
            } catch (error: Throwable) {
                logger.error("runtime {} failed to launch", runtime.name, error)
                rollbackFailedLaunch(context, error)
                throw error
            }
        }
    }

    /**
     * Stops started modules and uninstalls installed modules in reverse declaration order.
     *
     * This is idempotent for already stopped or never started lifecycles. Cleanup is best-effort: all remaining modules
     * are attempted even if one stop/uninstall call fails, then the first cleanup failure is thrown with later failures
     * attached as suppressed exceptions.
     */
    suspend fun stop() {
        lifecycleLock.withLock {
            if (currentState == NodeState.Stopped || currentState == NodeState.Unstarted) {
                return
            }
            val startedAt = System.nanoTime()
            logger.info("stopping runtime {}", runtime.name)
            changeState(NodeState.Stopping)
            try {
                val context = moduleContext()
                val cleanupError = cleanupRuntime(context)
                changeState(NodeState.Stopped)
                logger.info(
                    "runtime {} stopped in {} ms",
                    runtime.name,
                    (System.nanoTime() - startedAt) / 1_000_000,
                )
                cleanupError?.let { throw it }
            } catch (error: Throwable) {
                logger.error("runtime {} failed to stop", runtime.name, error)
                throw error
            }
        }
    }

    /**
     * Stops modules that were declared after [moduleName], in reverse declaration order.
     *
     * This is intended for host runtimes with their own shutdown lifecycle. For example, a Pekko `CoordinatedShutdown`
     * task can stop all application modules installed after `pekko-runtime` without recursively terminating the
     * ActorSystem from inside its own shutdown.
     */
    suspend fun stopAfter(moduleName: String) {
        require(moduleName.isNotBlank()) { "moduleName must not be blank" }
        if (currentState == NodeState.Stopping || currentState == NodeState.Stopped) {
            return
        }
        lifecycleLock.withLock {
            if (currentState == NodeState.Stopping || currentState == NodeState.Stopped) {
                return
            }
            val anchor = uniqueModuleIndex(moduleName)
            if (anchor == modules.lastIndex) {
                return
            }
            logger.info("stopping runtime {} modules after {}", runtime.name, moduleName)
            stopStartedModules(moduleContext(), modules.lastIndex downTo anchor + 1)
        }
    }

    private suspend fun changeState(newState: NodeState) {
        currentState = newState
        stateWriter?.invoke(newState)
        stateListeners[newState].orEmpty().forEach { it() }
    }

    private fun moduleContext(): ModuleContext {
        return ModuleContext(
            runtime = runtime,
            services = runtime.services,
            topology = topology,
        )
    }

    private suspend fun stopStartedModules(
        context: ModuleContext,
        indexes: IntProgression,
    ) {
        indexes.forEach { index ->
            if (index in startedModuleIndexes) {
                modules[index].stop(context)
                startedModuleIndexes -= index
            }
        }
    }

    private suspend fun cleanupRuntime(context: ModuleContext): Throwable? {
        var cleanupError: Throwable? = null
        modules.indices.reversed().forEach { index ->
            if (index in startedModuleIndexes) {
                cleanupError = collectCleanupError(cleanupError, "stop module ${modules[index].name}") {
                    modules[index].stop(context)
                }
                startedModuleIndexes -= index
            }
        }
        modules.indices.reversed().forEach { index ->
            if (index in installedModuleIndexes) {
                cleanupError = collectCleanupError(cleanupError, "uninstall module ${modules[index].name}") {
                    modules[index].uninstall(context)
                }
                installedModuleIndexes -= index
            }
        }
        return cleanupError
    }

    private suspend fun rollbackFailedLaunch(
        context: ModuleContext,
        startupError: Throwable,
    ) {
        logger.warn("rolling back failed runtime {} launch", runtime.name)
        runCleanupStep(startupError, "change state to stopping") {
            changeState(NodeState.Stopping)
        }
        cleanupRuntime(context)?.let(startupError::addSuppressed)
        runCleanupStep(startupError, "change state to stopped") {
            changeState(NodeState.Stopped)
        }
    }

    private suspend fun collectCleanupError(
        cleanupError: Throwable?,
        operation: String,
        block: suspend () -> Unit,
    ): Throwable? {
        return try {
            block()
            cleanupError
        } catch (error: Throwable) {
            logger.error("runtime {} cleanup failed during {}", runtime.name, operation, error)
            cleanupError?.also { it.addSuppressed(error) } ?: error
        }
    }

    private suspend fun runCleanupStep(
        startupError: Throwable,
        operation: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (cleanupError: Throwable) {
            logger.error("runtime {} rollback failed during {}", runtime.name, operation, cleanupError)
            startupError.addSuppressed(cleanupError)
        }
    }

    private fun uniqueModuleIndex(moduleName: String): Int {
        val indexes = modules.indices.filter { modules[it].name == moduleName }
        require(indexes.isNotEmpty()) { "module $moduleName not found in runtime ${runtime.name}" }
        require(indexes.size == 1) { "module $moduleName is not unique in runtime ${runtime.name}" }
        return indexes.single()
    }
}
