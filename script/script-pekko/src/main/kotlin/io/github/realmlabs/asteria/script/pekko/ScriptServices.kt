package io.github.realmlabs.asteria.script.pekko

import io.github.realmlabs.asteria.actor.ask
import io.github.realmlabs.asteria.cluster.config.ClusterViewNodeStatus
import io.github.realmlabs.asteria.cluster.config.ClusterViewService
import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.TraceAttributes
import io.github.realmlabs.asteria.observability.Tracer
import io.github.realmlabs.asteria.script.*
import kotlinx.coroutines.future.await
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import kotlin.time.Duration

/**
 * [ScriptRuntime] backed by the local [ScriptRuntimeActor].
 *
 * [execute] is limited to targets that produce one effective result according to the runtime's routing model.
 * [executeAll] installs a temporary collector actor and returns whatever matching results arrive before the timeout,
 * so missing actors or slow remote nodes produce partial or empty batches instead of a separate timeout exception.
 * [dispatch] only sends the command to the runtime actor and does not report delivery or execution failures.
 */
class PekkoScriptRuntime(
    val actor: ActorRef,
    private val system: ActorSystem,
    private val tracer: Tracer,
    private val metrics: Metrics,
    private val clusterView: ClusterViewService? = null,
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
                val expectedTargets = command.expectedTargets()
                ScriptExecutionBatchResult(
                    executionId = command.executionId,
                    results = results,
                    expectedTargets = expectedTargets,
                    missingTargets = expectedTargets.filterNot { expected -> results.any { it.matches(expected) } },
                )
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

    private suspend fun ScriptExecutionCommand.expectedTargets(): List<ScriptExecutionTarget> {
        return when (val target = target) {
            ScriptTarget.AllNodes -> clusterViewNodes()
            is ScriptTarget.ActorPath -> target.paths.map {
                ScriptExecutionTarget(ScriptExecutionTargetType.ActorPath, it)
            }

            is ScriptTarget.Entity -> target.ids.map {
                ScriptExecutionTarget(ScriptExecutionTargetType.Entity, "${target.kind.value}:$it")
            }

            is ScriptTarget.Node -> target.addresses.map {
                ScriptExecutionTarget(ScriptExecutionTargetType.Node, it)
            }

            is ScriptTarget.Role -> clusterViewNodes(role = target.role.value)
            is ScriptTarget.Singleton -> listOf(
                ScriptExecutionTarget(ScriptExecutionTargetType.Singleton, target.name.value),
            )
        }.distinct()
    }

    private suspend fun clusterViewNodes(role: String? = null): List<ScriptExecutionTarget> {
        val view = clusterView ?: return emptyList()
        return view.snapshot().nodes.asSequence()
            .filter { it.status != ClusterViewNodeStatus.Removed }
            .filter { role == null || it.roles.any { nodeRole -> nodeRole.value == role } }
            .mapNotNull { node -> node.address }
            .map { ScriptExecutionTarget(ScriptExecutionTargetType.Node, it) }
            .distinct()
            .toList()
    }

    private fun ScriptExecutionResult.matches(expected: ScriptExecutionTarget): Boolean {
        return when (expected.type) {
            ScriptExecutionTargetType.Node -> nodeAddress == expected.value || target == expected.value
            ScriptExecutionTargetType.ActorPath -> actorPath == expected.value || target == expected.value
            ScriptExecutionTargetType.Entity -> target == expected.value || target == expected.value.substringAfter(':')
            ScriptExecutionTargetType.Singleton -> target == expected.value
        }
    }
}

/**
 * Conservative local estimate used to reject obvious fan-out calls to [PekkoScriptRuntime.execute].
 */
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
