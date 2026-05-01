package io.github.mikai233.asteria.script.job

import io.github.mikai233.asteria.observability.MetricTags
import io.github.mikai233.asteria.observability.Metrics
import io.github.mikai233.asteria.observability.NoopMetrics
import io.github.mikai233.asteria.observability.NoopTracer
import io.github.mikai233.asteria.observability.TraceAttributes
import io.github.mikai233.asteria.observability.Tracer
import io.github.mikai233.asteria.script.ScriptExecutionBatchResult
import io.github.mikai233.asteria.script.ScriptExecutionCommand
import io.github.mikai233.asteria.script.ScriptExecutionResult
import io.github.mikai233.asteria.script.ScriptRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ScriptJobService(
    private val runtime: ScriptRuntime,
    private val store: ScriptJobStore,
    private val scope: CoroutineScope,
    private val tracer: Tracer = NoopTracer,
    private val metrics: Metrics = NoopMetrics,
) {
    suspend fun submit(
        command: ScriptExecutionCommand,
        timeout: Duration = 3.seconds,
        id: ScriptJobId = ScriptJobId(command.executionId),
    ): ScriptJob {
        return tracer.span("script.job.submit", jobTraceAttributes(id, command)) {
            metrics.counter("asteria.script.job.submitted.total", command.metricTags()).increment()
            val job = ScriptJob(id, command)
            store.create(job)
            scope.launch {
                runJob(job, timeout)
            }
            job
        }
    }

    suspend fun retryFailed(
        sourceJobId: ScriptJobId,
        retryCommand: ScriptExecutionCommand,
        timeout: Duration = 3.seconds,
        retryJobId: ScriptJobId = ScriptJobId(retryCommand.executionId),
    ): ScriptJob {
        return tracer.span("script.job.retry", jobTraceAttributes(retryJobId, retryCommand)) {
            metrics.counter("asteria.script.job.retry.total", retryCommand.metricTags()).increment()
            val source = requireNotNull(store.find(sourceJobId)) { "script job $sourceJobId not found" }
            require(source.results.any { !it.success }) { "script job $sourceJobId has no failed results" }
            val retry = ScriptJob(
                id = retryJobId,
                command = retryCommand,
                attempt = source.attempt + 1,
            )
            store.create(retry)
            scope.launch {
                runJob(retry, timeout)
            }
            retry
        }
    }

    suspend fun find(id: ScriptJobId): ScriptJob? {
        return store.find(id)
    }

    private suspend fun runJob(job: ScriptJob, timeout: Duration) {
        tracer.span("script.job.run", jobTraceAttributes(job.id, job.command)) {
            metrics.counter("asteria.script.job.running.total", job.command.metricTags()).increment()
            store.markRunning(job.id)
            val result = metrics.timer("asteria.script.job.duration", job.command.metricTags()).record {
                runCatching {
                    runtime.executeAll(job.command, timeout)
                }.getOrElse {
                    error(it)
                    ScriptExecutionBatchResult(
                        executionId = job.command.executionId,
                        results = listOf(
                            ScriptExecutionResult(
                                executionId = job.command.executionId,
                                success = false,
                                error = it.message,
                            ),
                        ),
                    )
                }
            }
            val status = result.status()
            store.appendResults(job.id, result.results)
            store.markFinished(job.id, status)
            metrics.counter(
                "asteria.script.job.finished.total",
                job.command.metricTags() + MetricTags.of("status" to status.name),
            ).increment()
        }
    }

    private fun ScriptExecutionBatchResult.status(): ScriptJobStatus {
        if (results.isEmpty()) {
            return ScriptJobStatus.Failed
        }
        return when {
            results.all { it.success } -> ScriptJobStatus.Completed
            results.any { it.success } -> ScriptJobStatus.PartialFailed
            else -> ScriptJobStatus.Failed
        }
    }

    private fun jobTraceAttributes(id: ScriptJobId, command: ScriptExecutionCommand): TraceAttributes {
        return TraceAttributes.of(
            "script.job_id" to id.value,
            "script.execution_id" to command.executionId,
            "script.target" to command.target.toString(),
            "script.engine" to command.artifact.engine,
        )
    }

    private fun ScriptExecutionCommand.metricTags(): MetricTags {
        return MetricTags.of(
            "target" to target.javaClass.simpleName,
            "engine" to artifact.engine,
        )
    }
}
