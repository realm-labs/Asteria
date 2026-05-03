package io.github.realmlabs.asteria.script.pekko

import io.github.realmlabs.asteria.actor.ask
import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.TraceAttributes
import io.github.realmlabs.asteria.observability.Tracer
import io.github.realmlabs.asteria.script.*
import kotlinx.coroutines.future.await
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import kotlin.time.Duration

class PekkoScriptRuntime(
    val actor: ActorRef,
    private val system: ActorSystem,
    private val tracer: Tracer,
    private val metrics: Metrics,
) : ScriptRuntime {
    override suspend fun execute(command: ScriptExecutionCommand, timeout: Duration): ScriptExecutionResult {
        require(command.target.resultCountHint() == 1) { "multi-target script execution requires executeAll" }
        return tracer.span("script.execute", command.traceAttributes()) {
            metrics.counter("asteria.script.execution.total", command.metricTags()).increment()
            metrics.timer("asteria.script.execution.duration", command.metricTags()).record {
                actor.ask<ScriptExecutionCommand, ScriptExecutionResult>(
                    message = command,
                    timeout = timeout,
                    tracer = tracer,
                    metrics = metrics,
                    spanName = "script.actor.ask",
                )
            }
        }
    }

    override suspend fun executeAll(
        command: ScriptExecutionCommand,
        timeout: Duration,
    ): ScriptExecutionBatchResult {
        return tracer.span("script.execute_all", command.traceAttributes()) {
            metrics.counter("asteria.script.execution.batch.total", command.metricTags()).increment()
            metrics.timer("asteria.script.execution.batch.duration", command.metricTags()).record {
                val results = system.collectScriptResults(actor, command, timeout).await()
                ScriptExecutionBatchResult(command.executionId, results)
            }
        }
    }

    override fun dispatch(command: ScriptExecutionCommand) {
        metrics.counter("asteria.script.dispatch.total", command.metricTags()).increment()
        actor.tell(command, ActorRef.noSender())
    }

    private fun ScriptExecutionCommand.traceAttributes(): TraceAttributes {
        return TraceAttributes.of(
            "script.execution_id" to executionId,
            "script.target" to target.toString(),
            "script.engine" to artifact.engine,
        )
    }

    private fun ScriptExecutionCommand.metricTags(): MetricTags {
        return MetricTags.of(
            "target" to target.javaClass.simpleName,
            "engine" to artifact.engine,
        )
    }
}

private fun ScriptTarget.resultCountHint(): Int {
    return when (this) {
        ScriptTarget.AllNodes -> 1
        is ScriptTarget.ActorPath -> paths.size
        is ScriptTarget.Entity -> ids.size
        is ScriptTarget.Node -> addresses.size
        is ScriptTarget.Role -> 1
        is ScriptTarget.Singleton -> 1
    }
}
