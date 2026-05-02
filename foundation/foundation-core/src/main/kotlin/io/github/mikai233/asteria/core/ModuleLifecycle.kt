package io.github.mikai233.asteria.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * Drives Asteria modules against a concrete [NodeRuntime].
 *
 * This is the formal entry for projects that already have their own node type, such as `PlayerNode` or `WorldNode`.
 * Build an [AsteriaApplication] for the module/topology definition, then bind that definition to the external runtime
 * with [AsteriaApplication.bind].
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
    private val startedModuleIndexes: MutableSet<Int> = linkedSetOf()
    private val lifecycleLock = Mutex()

    @Volatile
    private var currentState: NodeState = NodeState.Unstarted

    val state: NodeState
        get() = currentState

    fun onState(state: NodeState, listener: suspend () -> Unit) {
        stateListeners.getOrPut(state) { mutableListOf() }.add(listener)
    }

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
            changeState(NodeState.Starting)
            try {
                val context = moduleContext()
                runtime.services.register(AsteriaModuleLifecycle::class, this)
                modules.forEachIndexed { _, module -> module.install(context) }
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
                throw error
            }
        }
    }

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
                stopStartedModules(context, modules.indices.reversed())
                changeState(NodeState.Stopped)
                logger.info(
                    "runtime {} stopped in {} ms",
                    runtime.name,
                    (System.nanoTime() - startedAt) / 1_000_000,
                )
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

    private fun uniqueModuleIndex(moduleName: String): Int {
        val indexes = modules.indices.filter { modules[it].name == moduleName }
        require(indexes.isNotEmpty()) { "module $moduleName not found in runtime ${runtime.name}" }
        require(indexes.size == 1) { "module $moduleName is not unique in runtime ${runtime.name}" }
        return indexes.single()
    }
}
