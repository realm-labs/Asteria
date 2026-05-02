package io.github.mikai233.asteria.patch.pekko

import io.github.mikai233.asteria.cluster.config.RuntimeNodeConfig
import io.github.mikai233.asteria.core.AsteriaModule
import io.github.mikai233.asteria.core.ModuleContext
import io.github.mikai233.asteria.core.RoleKey
import io.github.mikai233.asteria.patch.InMemoryRuntimePatchNodeResultRepository
import io.github.mikai233.asteria.patch.PatchApplicationService
import io.github.mikai233.asteria.patch.PatchApplyResult
import io.github.mikai233.asteria.patch.PatchClusterApplicationService
import io.github.mikai233.asteria.patch.PatchId
import io.github.mikai233.asteria.patch.PatchNode
import io.github.mikai233.asteria.patch.PatchNodeClient
import io.github.mikai233.asteria.patch.PatchNodeProvider
import io.github.mikai233.asteria.patch.RuntimePatchNodeResultRepository
import io.github.mikai233.asteria.patch.RuntimePatchRepository
import java.io.Serializable
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.apache.pekko.actor.AbstractActor
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSelection
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.Address
import org.apache.pekko.actor.Props
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.cluster.Member
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.pattern.Patterns
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class PekkoPatchControlModule(
    private val timeout: Duration = 10.seconds,
) : AsteriaModule {
    override val name: String = "pekko-patch-control"

    private var localActor: ActorRef? = null

    override suspend fun install(context: ModuleContext) {
    }

    override suspend fun start(context: ModuleContext) {
        val system = context.services.get<ActorSystem>()
        val nodeProvider = PekkoPatchNodeProvider(system, timeout)
        val nodeClient = PekkoPatchNodeClient(system, timeout)

        context.services.register(PatchNodeProvider::class, nodeProvider)
        context.services.register(PatchNodeClient::class, nodeClient)

        val repository = context.services.find<RuntimePatchRepository>()
        if (repository != null) {
            val results = context.services.find<RuntimePatchNodeResultRepository>()
                ?: InMemoryRuntimePatchNodeResultRepository().also {
                    context.services.register(RuntimePatchNodeResultRepository::class, it)
                }
            context.services.register(
                PatchClusterApplicationService::class,
                PatchClusterApplicationService(repository, nodeProvider, nodeClient, results),
            )
        }

        val service = context.services.find<PatchApplicationService>() ?: return
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
            ask<Boolean>(system, node.address, PekkoPatchControlMessage.Disable(patchId), timeout)
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
                    replyTo.tell(service.apply(command.patchId), self)
                }
            }
            .match(PekkoPatchControlMessage.Disable::class.java) { command ->
                val replyTo = sender
                scope.launch {
                    replyTo.tell(service.disable(command.patchId), self)
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

private sealed interface PekkoPatchControlMessage : Serializable {
    data object GetStatus : PekkoPatchControlMessage

    data class Apply(val patchId: PatchId) : PekkoPatchControlMessage

    data class Disable(val patchId: PatchId) : PekkoPatchControlMessage
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
