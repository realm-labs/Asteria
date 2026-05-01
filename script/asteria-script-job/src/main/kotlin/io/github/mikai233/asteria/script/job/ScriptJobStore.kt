package io.github.mikai233.asteria.script.job

import io.github.mikai233.asteria.script.ScriptExecutionCommand
import io.github.mikai233.asteria.script.ScriptExecutionResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ScriptJobStore {
    suspend fun create(job: ScriptJob, items: List<ScriptJobItem>)

    suspend fun markItemRunning(
        jobId: ScriptJobId,
        itemId: ScriptJobItemId,
        attempt: Int,
        command: ScriptExecutionCommand,
    )

    suspend fun markItemFinished(
        jobId: ScriptJobId,
        itemId: ScriptJobItemId,
        attempt: Int,
        status: ScriptJobItemStatus,
        results: List<ScriptExecutionResult>,
        error: String? = null,
    )

    suspend fun find(id: ScriptJobId): ScriptJob?

    suspend fun listItems(id: ScriptJobId, status: ScriptJobItemStatus? = null): List<ScriptJobItem>

    suspend fun findItem(id: ScriptJobId, itemId: ScriptJobItemId): ScriptJobItem?
}

class InMemoryScriptJobStore : ScriptJobStore {
    private val mutex = Mutex()
    private val jobs: MutableMap<ScriptJobId, StoredScriptJob> = linkedMapOf()

    override suspend fun create(job: ScriptJob, items: List<ScriptJobItem>) {
        require(items.isNotEmpty()) { "script job items must not be empty" }
        mutex.withLock {
            check(job.id !in jobs) { "script job ${job.id} already exists" }
            val itemMap = linkedMapOf<ScriptJobItemId, ScriptJobItem>()
            items.forEach { item ->
                require(item.jobId == job.id) { "script job item ${item.id} belongs to another job" }
                check(item.id !in itemMap) { "script job item ${item.id} is duplicated" }
                itemMap[item.id] = item
            }
            jobs[job.id] = StoredScriptJob(
                job = job.copy(totalItems = itemMap.size),
                items = itemMap,
            )
        }
    }

    override suspend fun markItemRunning(
        jobId: ScriptJobId,
        itemId: ScriptJobItemId,
        attempt: Int,
        command: ScriptExecutionCommand,
    ) {
        require(attempt > 0) { "script job item attempt must be greater than 0" }
        update(jobId) { stored, now ->
            val item = stored.item(itemId)
            require(attempt == item.attempts.size + 1) {
                "script job item $itemId expected attempt ${item.attempts.size + 1}, got $attempt"
            }
            stored.items[itemId] = item.copy(
                status = ScriptJobItemStatus.Running,
                attempts = item.attempts + ScriptJobItemAttempt(
                    attempt = attempt,
                    command = command,
                    status = ScriptJobItemStatus.Running,
                    startedAtMillis = now,
                ),
                updatedAtMillis = now,
            )
            stored.refresh(now)
        }
    }

    override suspend fun markItemFinished(
        jobId: ScriptJobId,
        itemId: ScriptJobItemId,
        attempt: Int,
        status: ScriptJobItemStatus,
        results: List<ScriptExecutionResult>,
        error: String?,
    ) {
        require(status == ScriptJobItemStatus.Completed || status == ScriptJobItemStatus.Failed) {
            "script job item finish status must be terminal"
        }
        update(jobId) { stored, now ->
            val item = stored.item(itemId)
            require(item.attempts.isNotEmpty()) { "script job item $itemId has no running attempt" }
            require(item.attempts.last().attempt == attempt) {
                "script job item $itemId latest attempt is ${item.attempts.last().attempt}, got $attempt"
            }
            val updatedAttempt = item.attempts.last().copy(
                status = status,
                results = results,
                error = error,
                finishedAtMillis = now,
            )
            stored.items[itemId] = item.copy(
                status = status,
                results = results,
                attempts = item.attempts.dropLast(1) + updatedAttempt,
                updatedAtMillis = now,
            )
            stored.refresh(now)
        }
    }

    override suspend fun find(id: ScriptJobId): ScriptJob? {
        return mutex.withLock { jobs[id]?.job }
    }

    override suspend fun listItems(id: ScriptJobId, status: ScriptJobItemStatus?): List<ScriptJobItem> {
        return mutex.withLock {
            jobs[id]
                ?.items
                ?.values
                ?.filter { status == null || it.status == status }
                ?: emptyList()
        }
    }

    override suspend fun findItem(id: ScriptJobId, itemId: ScriptJobItemId): ScriptJobItem? {
        return mutex.withLock { jobs[id]?.items?.get(itemId) }
    }

    private suspend fun update(
        id: ScriptJobId,
        block: (StoredScriptJob, Long) -> Unit,
    ) {
        mutex.withLock {
            val stored = requireNotNull(jobs[id]) { "script job $id not found" }
            block(stored, System.currentTimeMillis())
        }
    }

    private data class StoredScriptJob(
        var job: ScriptJob,
        val items: MutableMap<ScriptJobItemId, ScriptJobItem>,
    ) {
        fun item(itemId: ScriptJobItemId): ScriptJobItem {
            return requireNotNull(items[itemId]) { "script job item $itemId not found" }
        }

        fun refresh(now: Long) {
            val values = items.values
            val completed = values.count { it.status == ScriptJobItemStatus.Completed }
            val failed = values.count { it.status == ScriptJobItemStatus.Failed }
            val running = values.any { it.status == ScriptJobItemStatus.Running }
            val pending = values.any { it.status == ScriptJobItemStatus.Pending }
            val status = when {
                running || pending -> ScriptJobStatus.Running
                failed == 0 -> ScriptJobStatus.Completed
                completed == 0 -> ScriptJobStatus.Failed
                else -> ScriptJobStatus.PartialFailed
            }
            job = job.copy(
                status = status,
                results = values.flatMap { it.results },
                completedItems = completed,
                failedItems = failed,
                updatedAtMillis = now,
            )
        }
    }
}
