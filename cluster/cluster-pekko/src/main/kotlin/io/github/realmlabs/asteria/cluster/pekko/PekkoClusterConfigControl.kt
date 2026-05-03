package io.github.realmlabs.asteria.cluster.pekko

import io.github.realmlabs.asteria.cluster.config.*
import io.github.realmlabs.asteria.config.ConfigReloadMonitor
import io.github.realmlabs.asteria.config.ConfigService
import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.apache.pekko.actor.*
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.cluster.Member
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.pattern.Patterns
import java.io.Serializable
import java.time.Instant
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Installs cluster-wide config status and reload control for a Pekko runtime.
 *
 * Nodes that also have a [ConfigService] expose a local control actor. Nodes without a config service can still install
 * this module to act as a GM/control client that fans out requests to the other cluster members.
 */
class PekkoClusterConfigControlModule : AsteriaModule {
    override val name: String = "pekko-cluster-config-control"

    private var localActor: ActorRef? = null

    override suspend fun install(context: ModuleContext) {
        val system = context.services.get<ActorSystem>()
        context.services.register(
            ClusterConfigControlService::class,
            PekkoClusterConfigControlService(system),
        )
    }

    override suspend fun start(context: ModuleContext) {
        val configService = context.services.find<ConfigService>() ?: return
        val system = context.services.get<ActorSystem>()
        val node = context.services.find<RuntimeNodeConfig>()
        val monitor = context.services.find<ConfigReloadMonitor>()
        localActor = system.actorOf(
            PekkoClusterConfigControlActor.props(configService, node, monitor),
            PEKKO_CLUSTER_CONFIG_CONTROL_ACTOR_NAME,
        )
    }

    override suspend fun stop(context: ModuleContext) {
        val actor = localActor ?: return
        context.services.get<ActorSystem>().stop(actor)
        localActor = null
    }
}

class PekkoClusterConfigControlService(
    private val system: ActorSystem,
) : ClusterConfigControlService {
    private val cluster: Cluster = Cluster.get(system)

    override suspend fun statuses(timeout: Duration): List<ClusterConfigNodeStatus> {
        return coroutineScope {
            activeMembers()
                .map { member -> async { status(member, timeout) } }
                .awaitAll()
        }
            .sortedBy { it.address }
    }

    override suspend fun reload(
        target: ClusterConfigReloadTarget,
        timeout: Duration,
    ): ClusterConfigReloadResult {
        val requestedAt = Instant.now()
        val members = selectMembers(target, timeout)
        val results = coroutineScope {
            members.selected.map { member -> async { reload(member, timeout) } }.awaitAll()
        } + members.missing
        return ClusterConfigReloadResult(
            target = target,
            requestedAt = requestedAt,
            results = results.sortedBy { it.address },
        )
    }

    private suspend fun status(
        member: Member,
        timeout: Duration,
    ): ClusterConfigNodeStatus {
        return try {
            ask<ClusterConfigNodeStatus>(member.address(), PekkoClusterConfigControlMessage.GetStatus, timeout)
        } catch (error: Throwable) {
            member.unreachableStatus(error)
        }
    }

    private suspend fun reload(
        member: Member,
        timeout: Duration,
    ): ClusterConfigNodeReloadResult {
        return try {
            ask<ClusterConfigNodeReloadResult>(member.address(), PekkoClusterConfigControlMessage.Reload, timeout)
        } catch (error: Throwable) {
            member.reloadFailure(error)
        }
    }

    private suspend fun selectMembers(
        target: ClusterConfigReloadTarget,
        timeout: Duration,
    ): MemberSelection {
        val members = activeMembers()
        return when (target) {
            ClusterConfigReloadTarget.All -> MemberSelection(members, emptyList())
            is ClusterConfigReloadTarget.Role -> MemberSelection(
                selected = members.filter { target.role in it.getRoles() },
                missing = emptyList(),
            )

            is ClusterConfigReloadTarget.Addresses -> MemberSelection(
                selected = members.filter { it.address().toString() in target.addresses },
                missing = target.addresses
                    .filter { address -> members.none { it.address().toString() == address } }
                    .map { address -> missingReloadResult(address, "cluster member not found for address") },
            )

            is ClusterConfigReloadTarget.Nodes -> {
                val statuses = statuses(timeout).filter { it.reachable }
                val addresses = statuses.filter { it.nodeId in target.nodeIds }.mapTo(linkedSetOf()) { it.address }
                MemberSelection(
                    selected = members.filter { it.address().toString() in addresses },
                    missing = target.nodeIds
                        .filter { nodeId -> statuses.none { it.nodeId == nodeId } }
                        .map { nodeId -> missingReloadResult(nodeId, "cluster member not found for node id", nodeId) },
                )
            }
        }
    }

    private fun activeMembers(): List<Member> {
        return cluster.state().members
            .filter { it.status() != MemberStatus.removed() }
            .sortedBy { it.address().toString() }
    }

    private suspend inline fun <reified T : Any> ask(
        address: Address,
        message: Any,
        timeout: Duration,
    ): T {
        val response = Patterns.ask(selection(address), message, timeout.toJavaDuration()).await()
        return response as? T
            ?: error("expected ${T::class.qualifiedName}, got ${response::class.qualifiedName}")
    }

    private fun selection(address: Address): ActorSelection {
        return system.actorSelection("$address/user/$PEKKO_CLUSTER_CONFIG_CONTROL_ACTOR_NAME")
    }
}

private data class MemberSelection(
    val selected: List<Member>,
    val missing: List<ClusterConfigNodeReloadResult>,
)

private class PekkoClusterConfigControlActor(
    private val configService: ConfigService,
    private val node: RuntimeNodeConfig?,
    private val monitor: ConfigReloadMonitor?,
) : AbstractActor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun createReceive(): Receive {
        return receiveBuilder()
            .matchEquals(PekkoClusterConfigControlMessage.GetStatus) {
                sender.tell(localStatus(), self)
            }
            .matchEquals(PekkoClusterConfigControlMessage.Reload) {
                val replyTo = sender
                scope.launch {
                    replyTo.tell(localReload(), self)
                }
            }
            .build()
    }

    override fun postStop() {
        scope.cancel()
    }

    private fun localStatus(): ClusterConfigNodeStatus {
        val cluster = Cluster.get(context.system)
        val current = configService.currentOrNull()
        val lastFailure = monitor?.status(current)?.lastFailure
        return ClusterConfigNodeStatus(
            nodeId = node?.nodeId,
            address = cluster.selfAddress().toString(),
            roles = node?.roles ?: emptySet(),
            revision = current?.revision,
            reachable = true,
            message = lastFailure?.message,
        )
    }

    private suspend fun localReload(): ClusterConfigNodeReloadResult {
        val before = configService.currentOrNull()?.revision
        return try {
            val result = configService.reload()
            ClusterConfigNodeReloadResult(
                nodeId = node?.nodeId,
                address = Cluster.get(context.system).selfAddress().toString(),
                roles = node?.roles ?: emptySet(),
                previousRevision = result.previous?.revision ?: before,
                currentRevision = result.current.revision,
                status = ClusterConfigNodeReloadStatus.Succeeded,
            )
        } catch (error: Throwable) {
            ClusterConfigNodeReloadResult(
                nodeId = node?.nodeId,
                address = Cluster.get(context.system).selfAddress().toString(),
                roles = node?.roles ?: emptySet(),
                previousRevision = before,
                currentRevision = configService.currentOrNull()?.revision,
                status = ClusterConfigNodeReloadStatus.Failed,
                message = error.message ?: error::class.qualifiedName ?: "unknown",
            )
        }
    }

    companion object {
        fun props(
            configService: ConfigService,
            node: RuntimeNodeConfig?,
            monitor: ConfigReloadMonitor?,
        ): Props {
            return Props.create(PekkoClusterConfigControlActor::class.java) {
                PekkoClusterConfigControlActor(configService, node, monitor)
            }
        }
    }
}

private sealed interface PekkoClusterConfigControlMessage : Serializable {
    data object GetStatus : PekkoClusterConfigControlMessage
    data object Reload : PekkoClusterConfigControlMessage
}

private const val PEKKO_CLUSTER_CONFIG_CONTROL_ACTOR_NAME: String = "asteriaConfigControl"

private fun Member.unreachableStatus(error: Throwable): ClusterConfigNodeStatus {
    return ClusterConfigNodeStatus(
        nodeId = uniqueAddress().longUid().toString(),
        address = address().toString(),
        roles = getRoles().toSet(),
        revision = null,
        reachable = false,
        message = error.message ?: error::class.qualifiedName ?: "unknown",
    )
}

private fun Member.reloadFailure(error: Throwable): ClusterConfigNodeReloadResult {
    return ClusterConfigNodeReloadResult(
        nodeId = uniqueAddress().longUid().toString(),
        address = address().toString(),
        roles = getRoles().toSet(),
        previousRevision = null,
        currentRevision = null,
        status = if (error is TimeoutException) {
            ClusterConfigNodeReloadStatus.Timeout
        } else {
            ClusterConfigNodeReloadStatus.Unreachable
        },
        message = error.message ?: error::class.qualifiedName ?: "unknown",
    )
}

private fun missingReloadResult(
    address: String,
    message: String,
    nodeId: String? = null,
): ClusterConfigNodeReloadResult {
    return ClusterConfigNodeReloadResult(
        nodeId = nodeId,
        address = address,
        previousRevision = null,
        currentRevision = null,
        status = ClusterConfigNodeReloadStatus.Skipped,
        message = message,
    )
}
