package io.github.mikai233.asteria.script.job

import io.github.mikai233.asteria.script.ScriptArtifact
import io.github.mikai233.asteria.script.ScriptExecutionBatchResult
import io.github.mikai233.asteria.script.ScriptExecutionCommand
import io.github.mikai233.asteria.script.ScriptExecutionMetadata
import io.github.mikai233.asteria.script.ScriptExecutionRequest
import io.github.mikai233.asteria.script.ScriptExecutionResult
import io.github.mikai233.asteria.script.ScriptExecutionScope
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
            val items = repository.listItems(ScriptJobId("job-1")).items

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
    fun repositoryListsItemsWithPagination() = runBlocking {
        val repository = InMemoryScriptJobRepository()
        val jobId = ScriptJobId("job-1")
        repository.create(
            ScriptJob(jobId, command("job-1")),
            listOf(
                ScriptJobItem(ScriptJobItemId("1"), jobId, ScriptTarget.Node(listOf("node-1"))),
                ScriptJobItem(ScriptJobItemId("2"), jobId, ScriptTarget.Node(listOf("node-2"))),
                ScriptJobItem(ScriptJobItemId("3"), jobId, ScriptTarget.Node(listOf("node-3"))),
            ),
        )

        val page = repository.listItems(jobId, ScriptJobItemQuery(offset = 1, limit = 1))

        assertEquals(3L, page.total)
        assertEquals(1, page.items.size)
        assertEquals(ScriptJobItemId("2"), page.items.single().id)
        assertEquals(2, page.nextOffset)
    }

    @Test
    fun repositoryListsJobsWithQuery() = runBlocking {
        val repository = InMemoryScriptJobRepository()
        repository.create(
            ScriptJob(ScriptJobId("job-1"), command("job-1", requester = "alice")),
            listOf(ScriptJobItem(ScriptJobItemId("1"), ScriptJobId("job-1"), ScriptTarget.Node(listOf("node-1")))),
        )
        repository.create(
            ScriptJob(ScriptJobId("job-2"), command("job-2", requester = "bob")),
            listOf(ScriptJobItem(ScriptJobItemId("1"), ScriptJobId("job-2"), ScriptTarget.Node(listOf("node-1")))),
        )

        val page = repository.listJobs(ScriptJobQuery(requester = "alice"))

        assertEquals(1L, page.total)
        assertEquals(listOf(ScriptJobId("job-1")), page.jobs.map { it.id })
    }

    @Test
    fun repositoryClaimsOnlyAvailablePendingItems() = runBlocking {
        val repository = InMemoryScriptJobRepository()
        val jobId = ScriptJobId("job-1")
        repository.create(
            ScriptJob(jobId, command("job-1")),
            listOf(
                ScriptJobItem(ScriptJobItemId("1"), jobId, ScriptTarget.Node(listOf("node-1"))),
                ScriptJobItem(ScriptJobItemId("2"), jobId, ScriptTarget.Node(listOf("node-2"))),
            ),
        )

        val first = repository.claimPendingItems(jobId, "worker-1", limit = 1, leaseUntilMillis = 2_000, nowMillis = 1_000)
        val second = repository.claimPendingItems(jobId, "worker-2", limit = 2, leaseUntilMillis = 2_000, nowMillis = 1_000)
        val expired = repository.claimPendingItems(jobId, "worker-3", limit = 1, leaseUntilMillis = 3_000, nowMillis = 2_001)

        assertEquals(listOf(ScriptJobItemId("1")), first.map { it.id })
        assertEquals("worker-1", first.single().leaseOwner)
        assertEquals(listOf(ScriptJobItemId("2")), second.map { it.id })
        assertEquals(listOf(ScriptJobItemId("1")), expired.map { it.id })
        assertEquals("worker-3", expired.single().leaseOwner)
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

    @Test
    fun resumeIncompleteJobsRunsPendingItems() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val repository = InMemoryScriptJobRepository()
        val runtime = FakeScriptRuntime(
            ScriptExecutionBatchResult(
                executionId = "job-1.1.1",
                results = listOf(ScriptExecutionResult("job-1.1.1", success = true, nodeAddress = "node-1")),
            ),
        )
        val service = ScriptJobService(runtime, repository, scope)
        val jobId = ScriptJobId("job-1")
        repository.create(
            ScriptJob(jobId, command("job-1", listOf("node-1"))),
            listOf(ScriptJobItem(ScriptJobItemId("1"), jobId, ScriptTarget.Node(listOf("node-1")))),
        )

        try {
            val resumed = service.resumeIncompleteJobs()
            val item = awaitItem(repository, jobId, ScriptJobItemId("1"), ScriptJobItemStatus.Completed)

            assertEquals(listOf(jobId), resumed.map { it.id })
            assertEquals(ScriptJobItemStatus.Completed, item.status)
            assertEquals(1, item.attempts.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun repositoryExpiresLeasedRunningItems() = runBlocking {
        val repository = InMemoryScriptJobRepository()
        val jobId = ScriptJobId("job-1")
        val itemId = ScriptJobItemId("1")
        repository.create(
            ScriptJob(jobId, command("job-1", listOf("node-1"))),
            listOf(ScriptJobItem(itemId, jobId, ScriptTarget.Node(listOf("node-1")))),
        )
        val now = System.currentTimeMillis()
        val claimed = repository.claimPendingItems(
            jobId,
            "worker-1",
            limit = 1,
            leaseUntilMillis = now + 1_000,
            nowMillis = now,
        )
        repository.markItemRunning(
            jobId = jobId,
            itemId = claimed.single().id,
            attempt = 1,
            command = command("job-1.1.1", listOf("node-1")),
            leaseOwner = "worker-1",
            leaseUntilMillis = now + 1_000,
        )

        val expired = repository.expireLeasedRunningItems(jobId, nowMillis = now + 1_001)
        val job = assertNotNull(repository.find(jobId))

        assertEquals(listOf(itemId), expired.map { it.id })
        assertEquals(ScriptJobItemStatus.Failed, expired.single().status)
        assertEquals("script job item lease expired", expired.single().attempts.single().error)
        assertEquals(ScriptJobStatus.Failed, job.status)
    }

    @Test
    fun repositoryRenewsRunningItemLease() = runBlocking {
        val repository = InMemoryScriptJobRepository()
        val jobId = ScriptJobId("job-1")
        val itemId = ScriptJobItemId("1")
        repository.create(
            ScriptJob(jobId, command("job-1", listOf("node-1"))),
            listOf(ScriptJobItem(itemId, jobId, ScriptTarget.Node(listOf("node-1")))),
        )
        val now = System.currentTimeMillis()
        repository.markItemRunning(
            jobId = jobId,
            itemId = itemId,
            attempt = 1,
            command = command("job-1.1.1", listOf("node-1")),
            leaseOwner = "worker-1",
            leaseUntilMillis = now + 1_000,
        )

        val renewed = repository.renewRunningItemLease(
            jobId = jobId,
            itemId = itemId,
            attempt = 1,
            leaseOwner = "worker-1",
            leaseUntilMillis = now + 2_000,
        )
        val item = assertNotNull(repository.findItem(jobId, itemId))

        assertTrue(renewed)
        assertEquals(now + 2_000, item.leaseUntilMillis)
    }

    @Test
    fun cancelJobCancelsPendingItems() = runBlocking {
        val repository = InMemoryScriptJobRepository()
        val jobId = ScriptJobId("job-1")
        repository.create(
            ScriptJob(jobId, command("job-1")),
            listOf(
                ScriptJobItem(ScriptJobItemId("1"), jobId, ScriptTarget.Node(listOf("node-1"))),
                ScriptJobItem(ScriptJobItemId("2"), jobId, ScriptTarget.Node(listOf("node-2"))),
            ),
        )

        val job = assertNotNull(
            repository.cancelJob(
                jobId,
                ScriptJobCancellation(requestedBy = "gm-1", reason = "mistake"),
            ),
        )
        val items = repository.listItems(jobId).items

        assertEquals(ScriptJobStatus.Cancelled, job.status)
        assertEquals(2, job.cancelledItems)
        assertEquals(listOf(ScriptJobItemStatus.Cancelled, ScriptJobItemStatus.Cancelled), items.map { it.status })
        assertTrue(items.all { it.cancelRequestedBy == "gm-1" })
    }

    @Test
    fun cancelRunningItemFinishesAsCancelled() = runBlocking {
        val repository = InMemoryScriptJobRepository()
        val jobId = ScriptJobId("job-1")
        val itemId = ScriptJobItemId("1")
        repository.create(
            ScriptJob(jobId, command("job-1", listOf("node-1"))),
            listOf(ScriptJobItem(itemId, jobId, ScriptTarget.Node(listOf("node-1")))),
        )
        val now = System.currentTimeMillis()
        repository.markItemRunning(
            jobId = jobId,
            itemId = itemId,
            attempt = 1,
            command = command("job-1.1.1", listOf("node-1")),
            leaseOwner = "worker-1",
            leaseUntilMillis = now + 1_000,
        )

        val cancelled = assertNotNull(
            repository.cancelItem(
                jobId,
                itemId,
                ScriptJobCancellation(requestedBy = "gm-1", reason = "stop"),
            ),
        )
        repository.markItemFinished(
            jobId = jobId,
            itemId = itemId,
            attempt = 1,
            leaseOwner = "worker-1",
            status = ScriptJobItemStatus.Completed,
            results = listOf(ScriptExecutionResult("job-1.1.1", success = true)),
        )
        val item = assertNotNull(repository.findItem(jobId, itemId))
        val job = assertNotNull(repository.find(jobId))

        assertEquals(ScriptJobItemStatus.Running, cancelled.status)
        assertEquals("gm-1", cancelled.cancelRequestedBy)
        assertEquals(ScriptJobItemStatus.Cancelled, item.status)
        assertEquals(ScriptJobItemStatus.Cancelled, item.attempts.single().status)
        assertEquals(ScriptJobStatus.Cancelled, job.status)
    }

    @Test
    fun staleWorkerCannotFinishItemAfterLeaseExpired() = runBlocking {
        val repository = InMemoryScriptJobRepository()
        val jobId = ScriptJobId("job-1")
        val itemId = ScriptJobItemId("1")
        repository.create(
            ScriptJob(jobId, command("job-1", listOf("node-1"))),
            listOf(ScriptJobItem(itemId, jobId, ScriptTarget.Node(listOf("node-1")))),
        )
        val now = System.currentTimeMillis()
        repository.markItemRunning(
            jobId = jobId,
            itemId = itemId,
            attempt = 1,
            command = command("job-1.1.1", listOf("node-1")),
            leaseOwner = "worker-1",
            leaseUntilMillis = now + 1_000,
        )
        repository.expireLeasedRunningItems(jobId, nowMillis = now + 1_001)

        val finished = repository.markItemFinished(
            jobId = jobId,
            itemId = itemId,
            attempt = 1,
            leaseOwner = "worker-1",
            status = ScriptJobItemStatus.Completed,
            results = listOf(ScriptExecutionResult("job-1.1.1", success = true)),
        )
        val item = assertNotNull(repository.findItem(jobId, itemId))

        assertEquals(false, finished)
        assertEquals(ScriptJobItemStatus.Failed, item.status)
    }

    @Test
    fun cancellationTokenObservesCancelRequest() = runBlocking {
        val repository = InMemoryScriptJobRepository()
        val jobId = ScriptJobId("job-1")
        val itemId = ScriptJobItemId("1")
        repository.create(
            ScriptJob(jobId, command("job-1", listOf("node-1"))),
            listOf(ScriptJobItem(itemId, jobId, ScriptTarget.Node(listOf("node-1")))),
        )
        repository.markItemRunning(
            jobId = jobId,
            itemId = itemId,
            attempt = 1,
            command = command("job-1.1.1", listOf("node-1")),
            leaseOwner = "worker-1",
            leaseUntilMillis = System.currentTimeMillis() + 1_000,
        )
        val token = ScriptJobCancellationProvider(repository).token(
            ScriptExecutionRequest(
                executionId = "job-1.1.1",
                target = ScriptTarget.Node(listOf("node-1")),
                artifact = ScriptArtifact("job-test", "fake", ByteArray(0)),
                scope = ScriptExecutionScope.Node,
                metadata = ScriptExecutionMetadata(
                    attributes = mapOf(
                        "script.jobId" to jobId.value,
                        "script.itemId" to itemId.value,
                        "script.attempt" to "1",
                    ),
                ),
            ),
        )

        assertEquals(false, token.isCancellationRequested())
        repository.cancelItem(jobId, itemId, ScriptJobCancellation(requestedBy = "gm-1"))

        assertTrue(token.isCancellationRequested())
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
        requester: String? = null,
    ): ScriptExecutionCommand {
        return ScriptExecutionCommand(
            executionId = executionId,
            target = ScriptTarget.Node(nodes),
            artifact = ScriptArtifact("job-test", "fake", ByteArray(0)),
            metadata = ScriptExecutionMetadata(requester = requester),
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
