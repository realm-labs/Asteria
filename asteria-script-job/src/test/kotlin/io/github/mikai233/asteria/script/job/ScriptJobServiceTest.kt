package io.github.mikai233.asteria.script.job

import io.github.mikai233.asteria.script.ScriptArtifact
import io.github.mikai233.asteria.script.ScriptExecutionBatchResult
import io.github.mikai233.asteria.script.ScriptExecutionCommand
import io.github.mikai233.asteria.script.ScriptExecutionResult
import io.github.mikai233.asteria.script.ScriptRuntime
import io.github.mikai233.asteria.script.ScriptTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration

class ScriptJobServiceTest {
    @Test
    fun submitRunsScriptAndStoresResults() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val store = InMemoryScriptJobStore()
        val runtime = FakeScriptRuntime(
            ScriptExecutionBatchResult(
                executionId = "job-1",
                results = listOf(
                    ScriptExecutionResult("job-1", success = true, nodeAddress = "node-1"),
                    ScriptExecutionResult("job-1", success = false, nodeAddress = "node-2", error = "failed"),
                ),
            ),
        )
        val service = ScriptJobService(runtime, store, scope)

        try {
            service.submit(command("job-1"))
            val job = awaitJob(store, ScriptJobId("job-1"), ScriptJobStatus.PartialFailed)

            assertEquals(2, job.results.size)
            assertEquals(ScriptJobStatus.PartialFailed, job.status)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun retryFailedCreatesNextAttempt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val store = InMemoryScriptJobStore()
        val runtime = FakeScriptRuntime(
            ScriptExecutionBatchResult(
                executionId = "job-1",
                results = listOf(ScriptExecutionResult("job-1", success = false, nodeAddress = "node-1")),
            ),
            ScriptExecutionBatchResult(
                executionId = "job-1-retry-1",
                results = listOf(ScriptExecutionResult("job-1-retry-1", success = true, nodeAddress = "node-1")),
            ),
        )
        val service = ScriptJobService(runtime, store, scope)

        try {
            service.submit(command("job-1"))
            awaitJob(store, ScriptJobId("job-1"), ScriptJobStatus.Failed)

            service.retryFailed(ScriptJobId("job-1"), command("job-1-retry-1"))
            val retry = awaitJob(store, ScriptJobId("job-1-retry-1"), ScriptJobStatus.Completed)

            assertEquals(2, retry.attempt)
            assertEquals(ScriptJobStatus.Completed, retry.status)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun awaitJob(
        store: ScriptJobStore,
        id: ScriptJobId,
        status: ScriptJobStatus,
    ): ScriptJob {
        repeat(100) {
            val job = store.find(id)
            if (job?.status == status) {
                return job
            }
            yield()
        }
        return assertNotNull(store.find(id))
    }

    private fun command(executionId: String): ScriptExecutionCommand {
        return ScriptExecutionCommand(
            executionId = executionId,
            target = ScriptTarget.AllNodes,
            artifact = ScriptArtifact("job-test", "fake", ByteArray(0)),
        )
    }
}

private class FakeScriptRuntime(
    private vararg val results: ScriptExecutionBatchResult,
) : ScriptRuntime {
    private var index: Int = 0

    override suspend fun execute(command: ScriptExecutionCommand, timeout: Duration): ScriptExecutionResult {
        return executeAll(command, timeout).results.single()
    }

    override suspend fun executeAll(command: ScriptExecutionCommand, timeout: Duration): ScriptExecutionBatchResult {
        return results[index++]
    }

    override fun dispatch(command: ScriptExecutionCommand) {
    }
}
