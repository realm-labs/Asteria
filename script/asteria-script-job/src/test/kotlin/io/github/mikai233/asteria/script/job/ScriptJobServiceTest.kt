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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class ScriptJobServiceTest {
    @Test
    fun submitCreatesItemsAndStoresResults() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val repository = InMemoryScriptJobRepository()
        val runtime = FakeScriptRuntime(
            ScriptExecutionBatchResult(
                executionId = "job-1.1.1",
                results = listOf(
                    ScriptExecutionResult("job-1.1.1", success = true, nodeAddress = "node-1"),
                ),
            ),
            ScriptExecutionBatchResult(
                executionId = "job-1.2.1",
                results = listOf(
                    ScriptExecutionResult("job-1.2.1", success = false, nodeAddress = "node-2", error = "failed"),
                ),
            ),
        )
        val service = ScriptJobService(runtime, repository, scope)

        try {
            val submitted = service.submit(command("job-1"))
            val job = awaitJob(repository, ScriptJobId("job-1"), ScriptJobStatus.PartialFailed)
            val items = repository.listItems(ScriptJobId("job-1"))

            assertEquals(2, submitted.totalItems)
            assertEquals(2, job.totalItems)
            assertEquals(1, job.completedItems)
            assertEquals(1, job.failedItems)
            assertEquals(ScriptJobStatus.PartialFailed, job.status)
            assertEquals(2, items.sumOf { it.results.size })
            assertEquals(listOf(ScriptJobItemStatus.Completed, ScriptJobItemStatus.Failed), items.map { it.status })
            assertTrue(items.all { it.attempts.size == 1 })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun retryItemAppendsNextAttempt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val repository = InMemoryScriptJobRepository()
        val runtime = FakeScriptRuntime(
            ScriptExecutionBatchResult(
                executionId = "job-1.1.1",
                results = listOf(ScriptExecutionResult("job-1.1.1", success = false, nodeAddress = "node-1")),
            ),
            ScriptExecutionBatchResult(
                executionId = "job-1.1.2",
                results = listOf(ScriptExecutionResult("job-1.1.2", success = true, nodeAddress = "node-1")),
            ),
        )
        val service = ScriptJobService(runtime, repository, scope)

        try {
            service.submit(command("job-1", listOf("node-1")))
            awaitJob(repository, ScriptJobId("job-1"), ScriptJobStatus.Failed)

            service.retryItem(ScriptJobId("job-1"), ScriptJobItemId("1"))
            val retry = awaitItem(repository, ScriptJobId("job-1"), ScriptJobItemId("1"), ScriptJobItemStatus.Completed)
            val job = awaitJob(repository, ScriptJobId("job-1"), ScriptJobStatus.Completed)

            assertEquals(2, retry.attempts.size)
            assertEquals(ScriptJobItemStatus.Completed, retry.status)
            assertEquals(ScriptJobStatus.Completed, job.status)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun awaitJob(
        repository: ScriptJobRepository,
        id: ScriptJobId,
        status: ScriptJobStatus,
    ): ScriptJob {
        repeat(100) {
            val job = repository.find(id)
            if (job?.status == status) {
                return job
            }
            delay(10)
        }
        return assertNotNull(repository.find(id))
    }

    private suspend fun awaitItem(
        repository: ScriptJobRepository,
        jobId: ScriptJobId,
        itemId: ScriptJobItemId,
        status: ScriptJobItemStatus,
    ): ScriptJobItem {
        repeat(100) {
            val item = repository.findItem(jobId, itemId)
            if (item?.status == status) {
                return item
            }
            delay(10)
        }
        return assertNotNull(repository.findItem(jobId, itemId))
    }

    private fun command(
        executionId: String,
        nodes: List<String> = listOf("node-1", "node-2"),
    ): ScriptExecutionCommand {
        return ScriptExecutionCommand(
            executionId = executionId,
            target = ScriptTarget.Node(nodes),
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
