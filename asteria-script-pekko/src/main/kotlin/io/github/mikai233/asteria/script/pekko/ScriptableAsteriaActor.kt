package io.github.mikai233.asteria.script.pekko

import io.github.mikai233.asteria.actor.AsteriaActor
import io.github.mikai233.asteria.core.NodeRuntime
import io.github.mikai233.asteria.script.DefaultActorScriptContext
import io.github.mikai233.asteria.script.ScriptExecutionRequest
import io.github.mikai233.asteria.script.ScriptExecutionResult
import io.github.mikai233.asteria.script.ScriptExecutionScope
import io.github.mikai233.asteria.script.ScriptRunner
import io.github.mikai233.asteria.script.ScriptTarget
import org.apache.pekko.actor.ActorRef
import scala.PartialFunction
import scala.runtime.BoxedUnit

abstract class ScriptableAsteriaActor<N : NodeRuntime>(
    runtime: N,
) : AsteriaActor<N>(runtime) {
    override fun aroundReceive(receive: PartialFunction<Any, BoxedUnit>?, msg: Any?) {
        when (msg) {
            is ExecuteActorScript -> executeScript(msg, sender)
            is ExecuteEntityActorScript -> executeScript(msg.toActorScript(), sender)
            else -> super.aroundReceive(receive, msg)
        }
    }

    private fun executeScript(command: ExecuteActorScript, replyTo: ActorRef) {
        val runner = runtime.services.get<ScriptRunner>()
        launch {
            val request = ScriptExecutionRequest(
                executionId = command.executionId,
                target = command.target ?: ScriptTarget.ActorPath(self.path().toString()),
                artifact = command.artifact,
                scope = ScriptExecutionScope.Actor,
                actorPath = self.path().toString(),
            )
            val result = runner.execute(
                request = request,
                context = DefaultActorScriptContext(runtime, command.artifact, this@ScriptableAsteriaActor),
                defaultResult = { success(command) },
                failureResult = {
                logger.error(it, "script {} failed on actor {}", command.executionId, self)
                failure(command, it)
                },
            )
            replyTo.tell(result, self)
        }
    }

    private fun ExecuteEntityActorScript.toActorScript(): ExecuteActorScript {
        return ExecuteActorScript(executionId, artifact, target)
    }

    private fun success(command: ExecuteActorScript): ScriptExecutionResult {
        return ScriptExecutionResult(
            executionId = command.executionId,
            success = true,
            target = command.target?.displayName(),
            actorPath = self.path().toString(),
        )
    }

    private fun failure(command: ExecuteActorScript, error: Throwable): ScriptExecutionResult {
        return ScriptExecutionResult(
            executionId = command.executionId,
            success = false,
            target = command.target?.displayName(),
            error = error.message,
            actorPath = self.path().toString(),
        )
    }

    private fun ScriptTarget.displayName(): String {
        return when (this) {
            ScriptTarget.AllNodes -> "all-nodes"
            is ScriptTarget.ActorPath -> path
            is ScriptTarget.Entity -> id
            is ScriptTarget.Node -> address
            is ScriptTarget.Role -> role.value
            is ScriptTarget.Singleton -> name.value
        }
    }
}
