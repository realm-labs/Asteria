package io.github.realmlabs.asteria.script.pekko

import io.github.realmlabs.asteria.actor.AsteriaActor
import io.github.realmlabs.asteria.script.*
import org.apache.pekko.actor.AbstractActor
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.japi.pf.ReceiveBuilder

/**
 * Script execution component for Asteria actors.
 *
 * Use this when an actor should support GM script execution but should not inherit from a script-specific base class.
 */
class ActorScriptSupport(
    private val actor: AsteriaActor<*>,
) {
    fun receive(): AbstractActor.Receive {
        return ReceiveBuilder.create()
            .match(ExecuteActorScript::class.java) { executeScript(it, actor.sender) }
            .match(ExecuteEntityActorScript::class.java) { executeScript(it.toActorScript(), actor.sender) }
            .build()
    }

    private fun executeScript(command: ExecuteActorScript, replyTo: ActorRef) {
        val runner = actor.runtime.services.get<ScriptRunner>()
        actor.launch {
            val request = ScriptExecutionRequest(
                executionId = command.executionId,
                target = command.target ?: ScriptTarget.ActorPath(listOf(actor.self.path().toString())),
                artifact = command.artifact,
                scope = ScriptExecutionScope.Actor,
                metadata = command.metadata,
                actorPath = actor.self.path().toString(),
            )
            val result = runner.execute(
                request = request,
                context = DefaultActorScriptContext(actor.runtime, request, actor),
                defaultResult = { success(command) },
                failureResult = {
                    actor.logger.error(it, "script {} failed on actor {}", command.executionId, actor.self)
                    failure(command, it)
                },
            )
            replyTo.tell(result, actor.self)
        }
    }

    private fun ExecuteEntityActorScript.toActorScript(): ExecuteActorScript {
        return ExecuteActorScript(executionId, artifact, target, metadata)
    }

    private fun success(command: ExecuteActorScript): ScriptExecutionResult {
        return ScriptExecutionResult(
            executionId = command.executionId,
            success = true,
            target = command.target?.displayName(),
            actorPath = actor.self.path().toString(),
        )
    }

    private fun failure(command: ExecuteActorScript, error: Throwable): ScriptExecutionResult {
        return ScriptExecutionResult(
            executionId = command.executionId,
            success = false,
            target = command.target?.displayName(),
            error = error.message,
            actorPath = actor.self.path().toString(),
        )
    }

    private fun ScriptTarget.displayName(): String {
        return when (this) {
            ScriptTarget.AllNodes -> "all-nodes"
            is ScriptTarget.ActorPath -> paths.joinToString(",")
            is ScriptTarget.Entity -> ids.joinToString(",")
            is ScriptTarget.Node -> addresses.joinToString(",")
            is ScriptTarget.Role -> role.value
            is ScriptTarget.Singleton -> name.value
        }
    }
}
