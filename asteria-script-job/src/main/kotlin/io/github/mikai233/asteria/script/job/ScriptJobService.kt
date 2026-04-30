package io.github.mikai233.asteria.script.job

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
) {
    suspend fun submit(
        command: ScriptExecutionCommand,
        timeout: Duration = 3.seconds,
        id: ScriptJobId = ScriptJobId(command.executionId),
    ): ScriptJob {
        val job = ScriptJob(id, command)
        store.create(job)
        scope.launch {
            runJob(job, timeout)
        }
        return job
    }

    suspend fun retryFailed(
        sourceJobId: ScriptJobId,
        retryCommand: ScriptExecutionCommand,
        timeout: Duration = 3.seconds,
        retryJobId: ScriptJobId = ScriptJobId(retryCommand.executionId),
    ): ScriptJob {
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
        return retry
    }

    suspend fun find(id: ScriptJobId): ScriptJob? {
        return store.find(id)
    }

    private suspend fun runJob(job: ScriptJob, timeout: Duration) {
        store.markRunning(job.id)
        val result = runCatching {
            runtime.executeAll(job.command, timeout)
        }.getOrElse {
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
        store.appendResults(job.id, result.results)
        store.markFinished(job.id, result.status())
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
}
