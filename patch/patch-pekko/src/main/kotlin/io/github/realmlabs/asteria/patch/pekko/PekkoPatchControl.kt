package io.github.realmlabs.asteria.patch.pekko

import io.github.realmlabs.asteria.cluster.config.*
import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext
import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.patch.*
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.apache.pekko.actor.*
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.cluster.Member
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.pattern.Patterns
import java.io.Serializable
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Pekko integration that exposes local patch control through an actor and registers cluster patch clients.
 *
 * The module prefers an installed cluster view service when available; otherwise it asks live Pekko members for their
 * local patch status. Remote operations are bounded by [timeout] and surface timeout failures as node results.
 */
class PekkoPatchControlModule(
    private val timeout: Duration = 10.seconds,
    private val nodeProvider: PatchNodeProvider? = null,
) : AsteriaModule {
    override val name: String = "pekko-patch-control"

    private var localActor: ActorRef? = null

    override suspend fun install(context: ModuleContext) {
    }

    override suspend fun start(context: ModuleContext) {
        val system = context.services.get<ActorSystem>()
        val service = context.services.find<PatchApplicationService>()
        val resolvedNodeProvider = nodeProvider
            ?: context.services.find<ClusterViewService>()
                ?.let { ClusterViewPatchNodeProvider(it, service?.environment) }
            ?: context.services.find<ClusterTopologyProvider>()?.let {
                ClusterViewPatchNodeProvider(
                    TopologyClusterViewService(
                        topology = it,
                        appName = service?.environment?.appName ?: context.name,
                        version = service?.environment?.version,
                    ),
                    service?.environment,
                )
            }
            ?: PekkoPatchNodeProvider(system, timeout)
        val nodeClient = PekkoPatchNodeClient(system, timeout)

        context.services.register(PatchNodeProvider::class, resolvedNodeProvider)
        context.services.register(PatchNodeClient::class, nodeClient)

        val repository = context.services.find<RuntimePatchRepository>()
        if (repository != null) {
            val results = context.services.find<RuntimePatchNodeResultRepository>()
                ?: InMemoryRuntimePatchNodeResultRepository().also {
                    context.services.register(RuntimePatchNodeResultRepository::class, it)
                }
            context.services.register(
                PatchClusterApplicationService::class,
                PatchClusterApplicationService(repository, resolvedNodeProvider, nodeClient, results),
            )
        }

        service ?: return
        val node = context.services.find<RuntimeNodeConfig>()
        localActor = system.actorOf(
            PekkoPatchControlActor.props(service, node),
            PEKKO_PATCH_CONTROL_ACTOR_NAME,
        )
    }

    override suspend fun stop(context: ModuleContext) {
        val actor = localActor ?: return
        context.services.get<ActorSystem>().stop(actor)
        localActor = null
    }
}

/**
 * Adapts the configured cluster view into patch nodes.
 *
 * Nodes without an address or version are skipped because patch compatibility cannot be evaluated safely.
 */
class ClusterViewPatchNodeProvider(
    private val view: ClusterViewService,
    private val fallbackEnvironment: PatchEnvironment? = null,
) : PatchNodeProvider {
    override suspend fun nodes(): List<PatchNode> {
        return view.snapshot().nodes
            .filter { it.status != ClusterViewNodeStatus.Removed }
            .mapNotNull { node -> node.toPatchNode() }
            .sortedBy { it.address }
    }

    private fun ClusterViewNode.toPatchNode(): PatchNode? {
        val address = address ?: nodeId?.let { "node:$it" } ?: return null
        return PatchNode(
            nodeId = nodeId,
            address = address,
            appName = appName,
            version = version ?: fallbackEnvironment?.version ?: return null,
            roles = roles,
            modules = attributes[PATCH_MODULES_ATTRIBUTE].toStringSet(),
            capabilities = attributes[PATCH_CAPABILITIES_ATTRIBUTE].toStringSet(),
            status = status.toPatchNodeStatus(),
        )
    }

    private fun ClusterViewNodeStatus.toPatchNodeStatus(): PatchNodeStatus {
        return when (this) {
            ClusterViewNodeStatus.Reachable -> PatchNodeStatus.Reachable
            ClusterViewNodeStatus.Expected -> PatchNodeStatus.Expected
            ClusterViewNodeStatus.Unreachable -> PatchNodeStatus.Unreachable
            ClusterViewNodeStatus.Removed -> PatchNodeStatus.Removed
        }
    }
}

/**
 * Builds the local patch environment from Pekko cluster and runtime node metadata.
 */
class PekkoPatchEnvironmentProvider(
    private val version: String,
    private val appName: String? = null,
) : PatchEnvironmentProvider {
    init {
        require(version.isNotBlank()) { "patch environment version must not be blank" }
        appName?.let { require(it.isNotBlank()) { "patch environment app name must not be blank" } }
    }

    override suspend fun environment(context: ModuleContext): PatchEnvironment {
        val system = context.services.find<ActorSystem>()
        val node = context.services.find<RuntimeNodeConfig>()
        val attributes = node?.attributes ?: emptyMap()
        return PatchEnvironment(
            appName = appName ?: context.name,
            version = version,
            nodeAddress = system?.let { Cluster.get(it).selfAddress().toString() }
                ?: node?.let { "${it.host}:${it.port}" },
            roles = node?.roles?.mapTo(linkedSetOf(), ::RoleKey) ?: context.roles,
            modules = attributes[PATCH_MODULES_ATTRIBUTE].toStringSet(),
            capabilities = attributes[PATCH_CAPABILITIES_ATTRIBUTE].toStringSet(),
        )
    }
}

/**
 * Discovers patch-capable nodes by asking each active Pekko cluster member for local status.
 */
class PekkoPatchNodeProvider(
    private val system: ActorSystem,
    private val timeout: Duration = 10.seconds,
) : PatchNodeProvider {
    private val cluster: Cluster = Cluster.get(system)

    override suspend fun nodes(): List<PatchNode> {
        return coroutineScope {
            activeMembers()
                .map { member -> async { statusOrNull(member) } }
                .awaitAll()
                .filterNotNull()
                .sortedBy { it.address }
        }
    }

    private suspend fun statusOrNull(member: Member): PatchNode? {
        return runCatching {
            ask<PatchNode>(system, member.address(), PekkoPatchControlMessage.GetStatus, timeout)
        }.getOrNull()
    }

    private fun activeMembers(): List<Member> {
        return cluster.state().members
            .filter { it.status() != MemberStatus.removed() }
            .sortedBy { it.address().toString() }
    }
}

/**
 * Sends patch apply and disable commands to a node's Pekko patch control actor.
 */
class PekkoPatchNodeClient(
    private val system: ActorSystem,
    private val timeout: Duration = 10.seconds,
) : PatchNodeClient {
    override suspend fun apply(
        node: PatchNode,
        patchId: PatchId,
    ): PatchApplyResult {
        return try {
            ask<PatchApplyResult>(system, node.address, PekkoPatchControlMessage.Apply(patchId), timeout)
        } catch (error: Throwable) {
            throw if (error is TimeoutException) {
                TimeoutException("timed out applying patch $patchId on ${node.address}")
            } else {
                error
            }
        }
    }

    override suspend fun disable(
        node: PatchNode,
        patchId: PatchId,
    ): Boolean {
        return try {
            val result = ask<PekkoPatchDisableResult>(
                system,
                node.address,
                PekkoPatchControlMessage.Disable(patchId),
                timeout,
            )
            result.message?.let { error(it) }
            result.removed
        } catch (error: Throwable) {
            throw if (error is TimeoutException) {
                TimeoutException("timed out disabling patch $patchId on ${node.address}")
            } else {
                error
            }
        }
    }
}

private class PekkoPatchControlActor(
    private val service: PatchApplicationService,
    private val node: RuntimeNodeConfig?,
) : AbstractActor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun createReceive(): Receive {
        return receiveBuilder()
            .matchEquals(PekkoPatchControlMessage.GetStatus) {
                sender.tell(localStatus(), self)
            }
            .match(PekkoPatchControlMessage.Apply::class.java) { command ->
                val replyTo = sender
                scope.launch {
                    val result = runCatching {
                        service.apply(command.patchId)
                    }.getOrElse { error ->
                        PatchApplyResult.Failed(
                            patchId = command.patchId,
                            message = error.message ?: error::class.qualifiedName ?: "unknown",
                        )
                    }
                    replyTo.tell(result, self)
                }
            }
            .match(PekkoPatchControlMessage.Disable::class.java) { command ->
                val replyTo = sender
                scope.launch {
                    val result = runCatching {
                        PekkoPatchDisableResult(service.disable(command.patchId))
                    }.getOrElse { error ->
                        PekkoPatchDisableResult(
                            removed = false,
                            message = error.message ?: error::class.qualifiedName ?: "unknown",
                        )
                    }
                    replyTo.tell(result, self)
                }
            }
            .build()
    }

    override fun postStop() {
        scope.cancel()
    }

    private fun localStatus(): PatchNode {
        val environment = service.environment
        return PatchNode(
            nodeId = node?.nodeId,
            address = Cluster.get(context.system).selfAddress().toString(),
            appName = environment.appName,
            version = environment.version,
            roles = node?.roles?.mapTo(linkedSetOf(), ::RoleKey) ?: environment.roles,
            modules = environment.modules,
            capabilities = environment.capabilities,
        )
    }

    companion object {
        fun props(
            service: PatchApplicationService,
            node: RuntimeNodeConfig?,
        ): Props {
            return Props.create(PekkoPatchControlActor::class.java) {
                PekkoPatchControlActor(service, node)
            }
        }
    }
}

sealed interface PekkoPatchControlMessage : Serializable {
    data object GetStatus : PekkoPatchControlMessage {
        private fun readResolve(): Any = GetStatus
    }

    data class Apply(val patchId: PatchId) : PekkoPatchControlMessage

    data class Disable(val patchId: PatchId) : PekkoPatchControlMessage
}

data class PekkoPatchDisableResult(
    val removed: Boolean,
    val message: String? = null,
) : Serializable {
    init {
        message?.let { require(it.isNotBlank()) { "patch disable result message must not be blank" } }
    }
}

private suspend inline fun <reified T : Any> ask(
    system: ActorSystem,
    address: String,
    message: Any,
    timeout: Duration,
): T {
    val response = Patterns.ask(selection(system, address), message, timeout.toJavaDuration()).await()
    return response as? T
        ?: error("expected ${T::class.qualifiedName}, got ${response::class.qualifiedName}")
}

private suspend inline fun <reified T : Any> ask(
    system: ActorSystem,
    address: Address,
    message: Any,
    timeout: Duration,
): T {
    return ask(system, address.toString(), message, timeout)
}

private fun selection(
    system: ActorSystem,
    address: String,
): ActorSelection {
    return system.actorSelection("$address/user/$PEKKO_PATCH_CONTROL_ACTOR_NAME")
}

private const val PEKKO_PATCH_CONTROL_ACTOR_NAME: String = "asteriaPatchControl"
private const val PATCH_MODULES_ATTRIBUTE: String = "patch.modules"
private const val PATCH_CAPABILITIES_ATTRIBUTE: String = "patch.capabilities"

private fun String?.toStringSet(): Set<String> {
    return this
        ?.split(',', ';')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toCollection(linkedSetOf())
        ?: emptySet()
}
