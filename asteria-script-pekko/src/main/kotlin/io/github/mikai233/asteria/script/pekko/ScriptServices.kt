package io.github.mikai233.asteria.script.pekko

import io.github.mikai233.asteria.actor.ask
import io.github.mikai233.asteria.script.ScriptExecutionBatchResult
import io.github.mikai233.asteria.script.ScriptExecutionCommand
import io.github.mikai233.asteria.script.ScriptExecutionResult
import io.github.mikai233.asteria.script.ScriptRuntime
import kotlinx.coroutines.future.await
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import kotlin.time.Duration

class PekkoScriptRuntime(
    val actor: ActorRef,
    private val system: ActorSystem,
) : ScriptRuntime {
    override suspend fun execute(command: ScriptExecutionCommand, timeout: Duration): ScriptExecutionResult {
        return actor.ask<ScriptExecutionCommand, ScriptExecutionResult>(command, timeout)
    }

    override suspend fun executeAll(
        command: ScriptExecutionCommand,
        timeout: Duration,
    ): ScriptExecutionBatchResult {
        val results = system.collectScriptResults(actor, command, timeout).await()
        return ScriptExecutionBatchResult(command.executionId, results)
    }

    override fun dispatch(command: ScriptExecutionCommand) {
        actor.tell(command, ActorRef.noSender())
    }
}
