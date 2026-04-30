package io.github.mikai233.asteria.script.pekko

import io.github.mikai233.asteria.script.ScriptExecutionBatchResult
import io.github.mikai233.asteria.script.ScriptExecutionCommand
import io.github.mikai233.asteria.script.ScriptExecutionResult
import io.github.mikai233.asteria.script.ScriptRuntime
import kotlinx.coroutines.future.await
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.pattern.Patterns
import kotlin.time.Duration
import kotlin.time.toJavaDuration

class PekkoScriptRuntime(
    val actor: ActorRef,
    private val system: ActorSystem,
) : ScriptRuntime {
    override suspend fun execute(command: ScriptExecutionCommand, timeout: Duration): ScriptExecutionResult {
        val result = Patterns.ask(actor, command, timeout.toJavaDuration()).await()
        return result as? ScriptExecutionResult
            ?: error("script runtime returned unsupported result ${result::class.qualifiedName}")
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
