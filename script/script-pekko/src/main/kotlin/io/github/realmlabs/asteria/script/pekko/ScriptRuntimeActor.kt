package io.github.realmlabs.asteria.script.pekko

import io.github.realmlabs.asteria.actor.actorLogger
import io.github.realmlabs.asteria.cluster.pekko.EntityShardRegistry
import io.github.realmlabs.asteria.cluster.pekko.SingletonActorRegistry
import io.github.realmlabs.asteria.core.NodeRuntime
import io.github.realmlabs.asteria.script.*
import kotlinx.coroutines.*
import org.apache.pekko.actor.AbstractActor
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.cluster.pubsub.DistributedPubSub
import org.apache.pekko.cluster.pubsub.DistributedPubSubMediator.*

class ScriptRuntimeActor(
    private val runtime: NodeRuntime,
) : AbstractActor() {
    private val logger = actorLogger()
    private val job: Job = SupervisorJob()
    private val scope = CoroutineScope(context.dispatcher.asCoroutineDispatcher() + job)
    private lateinit var mediator: ActorRef

    override fun preStart() {
        super.preStart()
        mediator = DistributedPubSub.get(context.system).mediator()
        mediator.tell(Subscribe(ALL_NODES_TOPIC, self), self)
        runtime.roles.forEach { mediator.tell(Subscribe(roleTopic(it.value), self), self) }
    }

    override fun postStop() {
        job.cancel()
        super.postStop()
    }

    override fun createReceive(): Receive {
        return receiveBuilder()
            .match(ExecuteNodeScript::class.java) { handleDistributedNodeCommand(it) }
            .match(ScriptExecutionCommand::class.java) { handleCommand(it) }
            .match(SubscribeAck::class.java) {}
            .build()
    }

    private fun handleCommand(command: ScriptExecutionCommand) {
        val replyTo = sender
        when (val target = command.target) {
            ScriptTarget.AllNodes -> {
                executeOnThisNode(command, replyTo = replyTo)
                publishNodeCommand(ALL_NODES_TOPIC, command, replyTo)
            }

            is ScriptTarget.Role -> {
                if (target.role in runtime.roles) {
                    executeOnThisNode(command, replyTo = replyTo)
                }
                publishNodeCommand(roleTopic(target.role.value), command, replyTo)
            }

            is ScriptTarget.Node -> {
                val address = selfAddress()
                if (address in target.addresses) {
                    executeOnThisNode(command.copy(target = ScriptTarget.Node(listOf(address))), replyTo = replyTo)
                }
            }

            is ScriptTarget.ActorPath -> {
                target.paths.forEach { path ->
                    val singleTarget = ScriptTarget.ActorPath(listOf(path))
                    context.actorSelection(path)
                        .tell(actorCommand(command.copy(target = singleTarget), singleTarget), replyTo)
                }
            }

            is ScriptTarget.Entity -> {
                val shard = runtime.services.get<EntityShardRegistry>()[target.kind]
                target.ids.forEach { id ->
                    val singleTarget = ScriptTarget.Entity(target.kind, listOf(id))
                    shard.tell(
                        ExecuteEntityActorScript(
                            id = id,
                            executionId = command.executionId,
                            artifact = command.artifact,
                            target = singleTarget,
                            metadata = command.metadata,
                        ),
                        replyTo,
                    )
                }
            }

            is ScriptTarget.Singleton -> {
                runtime.services.get<SingletonActorRegistry>()[target.name]
                    .tell(actorCommand(command, target), replyTo)
            }
        }
    }

    private fun handleDistributedNodeCommand(message: ExecuteNodeScript) {
        if (message.originNodeAddress == selfAddress()) {
            return
        }
        val command = message.command
        val replyTo = sender
        when (val target = command.target) {
            ScriptTarget.AllNodes -> executeOnThisNode(command, replyTo = replyTo)
            is ScriptTarget.Role -> {
                if (target.role in runtime.roles) {
                    executeOnThisNode(command, replyTo = replyTo)
                }
            }

            is ScriptTarget.Node -> {
                val address = selfAddress()
                if (address in target.addresses) {
                    executeOnThisNode(command.copy(target = ScriptTarget.Node(listOf(address))), replyTo = replyTo)
                }
            }

            is ScriptTarget.ActorPath,
            is ScriptTarget.Entity,
            is ScriptTarget.Singleton,
                -> handleCommand(command)
        }
    }

    private fun publishNodeCommand(topic: String, command: ScriptExecutionCommand, replyTo: ActorRef) {
        mediator.tell(Publish(topic, ExecuteNodeScript(command, originNodeAddress = selfAddress())), replyTo)
    }

    private fun executeOnThisNode(command: ScriptExecutionCommand, replyTo: ActorRef) {
        val runner = runtime.services.get<ScriptRunner>()
        scope.launch {
            val request = ScriptExecutionRequest(
                executionId = command.executionId,
                target = command.target,
                artifact = command.artifact,
                scope = ScriptExecutionScope.Node,
                metadata = command.metadata,
                nodeAddress = selfAddress(),
            )
            val result = runner.execute(
                request = request,
                context = NodeScriptContext(runtime, request),
                defaultResult = { success(command, target = selfAddress()) },
                failureResult = {
                    logger.error(it, "script {} failed on node {}", command.executionId, selfAddress())
                    failure(command, it, target = selfAddress())
                },
            )
            replyTo.tell(result, self)
        }
    }

    private fun actorCommand(command: ScriptExecutionCommand, target: ScriptTarget): ExecuteActorScript {
        return ExecuteActorScript(command.executionId, command.artifact, target, command.metadata)
    }

    private fun success(command: ScriptExecutionCommand, target: String?): ScriptExecutionResult {
        return ScriptExecutionResult(command.executionId, success = true, target = target, nodeAddress = selfAddress())
    }

    private fun failure(command: ScriptExecutionCommand, error: Throwable, target: String?): ScriptExecutionResult {
        return ScriptExecutionResult(
            executionId = command.executionId,
            success = false,
            target = target,
            error = error.message,
            nodeAddress = selfAddress(),
        )
    }

    private fun selfAddress(): String {
        return Cluster.get(context.system).selfAddress().toString()
    }

    companion object {
        const val NAME = "asteriaScriptRuntime"
        const val ALL_NODES_TOPIC = "asteria.script.nodes.all"

        fun roleTopic(role: String): String {
            return "asteria.script.nodes.role.$role"
        }

        fun props(runtime: NodeRuntime): Props {
            return Props.create(ScriptRuntimeActor::class.java) { ScriptRuntimeActor(runtime) }
        }
    }
}
