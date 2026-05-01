package io.github.mikai233.asteria.script.pekko

import io.github.mikai233.asteria.actor.ask
import io.github.mikai233.asteria.observability.MetricTags
import io.github.mikai233.asteria.observability.Metrics
import io.github.mikai233.asteria.observability.TraceAttributes
import io.github.mikai233.asteria.observability.Tracer
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
    private val tracer: Tracer,
    private val metrics: Metrics,
) : ScriptRuntime {
    override suspend fun execute(command: ScriptExecutionCommand, timeout: Duration): ScriptExecutionResult {
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
