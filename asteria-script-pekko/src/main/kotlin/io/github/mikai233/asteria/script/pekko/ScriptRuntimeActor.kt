package io.github.mikai233.asteria.script.pekko

import io.github.mikai233.asteria.actor.actorLogger
import io.github.mikai233.asteria.cluster.pekko.EntityShardRegistry
import io.github.mikai233.asteria.cluster.pekko.SingletonActorRegistry
import io.github.mikai233.asteria.core.NodeRuntime
import io.github.mikai233.asteria.script.NodeScriptContext
import io.github.mikai233.asteria.script.ScriptExecutionCommand
import io.github.mikai233.asteria.script.ScriptExecutionRequest
import io.github.mikai233.asteria.script.ScriptExecutionResult
import io.github.mikai233.asteria.script.ScriptExecutionScope
import io.github.mikai233.asteria.script.ScriptRunner
import io.github.mikai233.asteria.script.ScriptTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.apache.pekko.actor.AbstractActor
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props
import org.apache.pekko.cluster.Cluster

class ScriptRuntimeActor(
    private val runtime: NodeRuntime,
) : AbstractActor() {
    private val logger = actorLogger()
    private val job: Job = SupervisorJob()
    private val scope = CoroutineScope(context.dispatcher.asCoroutineDispatcher() + job)

    override fun postStop() {
        job.cancel()
        super.postStop()
    }

    override fun createReceive(): Receive {
        return receiveBuilder()
            .match(ExecuteNodeScript::class.java) { handleCommand(it.command) }
            .match(ScriptExecutionCommand::class.java) { handleCommand(it) }
            .build()
    }

    private fun handleCommand(command: ScriptExecutionCommand) {
        val replyTo = sender
        when (val target = command.target) {
            ScriptTarget.AllNodes -> executeOnThisNode(command, replyTo = replyTo)
            is ScriptTarget.Role -> {
                if (target.role in runtime.roles) {
                    executeOnThisNode(command, replyTo = replyTo)
                }
            }

            is ScriptTarget.Node -> {
                if (target.address == selfAddress()) {
                    executeOnThisNode(command, replyTo = replyTo)
                }
            }

            is ScriptTarget.ActorPath -> {
                context.actorSelection(target.path).tell(actorCommand(command, target), replyTo)
            }

            is ScriptTarget.Entity -> {
                runtime.services.get<EntityShardRegistry>()[target.kind]
                    .tell(ExecuteEntityActorScript(target.id, command.executionId, command.artifact, target), replyTo)
            }

            is ScriptTarget.Singleton -> {
                runtime.services.get<SingletonActorRegistry>()[target.name]
                    .tell(actorCommand(command, target), replyTo)
            }
        }
    }

    private fun executeOnThisNode(command: ScriptExecutionCommand, replyTo: ActorRef) {
        val runner = runtime.services.get<ScriptRunner>()
        scope.launch {
            val request = ScriptExecutionRequest(
                executionId = command.executionId,
                target = command.target,
                artifact = command.artifact,
                scope = ScriptExecutionScope.Node,
                nodeAddress = selfAddress(),
            )
            val result = runner.execute(
                request = request,
                context = NodeScriptContext(runtime, command.artifact),
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
        return ExecuteActorScript(command.executionId, command.artifact, target)
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
        const val Name = "asteriaScriptRuntime"

        fun props(runtime: NodeRuntime): Props {
            return Props.create(ScriptRuntimeActor::class.java) { ScriptRuntimeActor(runtime) }
        }
    }
}
