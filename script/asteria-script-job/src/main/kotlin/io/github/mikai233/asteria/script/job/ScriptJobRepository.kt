package io.github.mikai233.asteria.script.job

import io.github.mikai233.asteria.script.ScriptExecutionCommand
import io.github.mikai233.asteria.script.ScriptExecutionResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Durable storage contract for script jobs.
 *
 * The repository is deliberately shaped around job semantics instead of generic CRUD. Implementations are responsible
 * for making item state transitions durable enough that submitted jobs can be inspected, audited, and retried after the
 * request that created them has returned.
 */
interface ScriptJobRepository {
    suspend fun create(job: ScriptJob, items: List<ScriptJobItem>)

    /**
     * Claims pending items for one worker without starting an attempt yet.
     *
     * Implementations must claim atomically enough that multiple workers using the same durable store cannot receive
     * the same pending item while its lease is still active.
     */
    suspend fun claimPendingItems(
        id: ScriptJobId,
        workerId: String,
        limit: Int,
        leaseUntilMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<ScriptJobItem>

    suspend fun markItemRunning(
        jobId: ScriptJobId,
        itemId: ScriptJobItemId,
        attempt: Int,
        command: ScriptExecutionCommand,
        leaseOwner: String,
        leaseUntilMillis: Long,
    )

    suspend fun markItemFinished(
        jobId: ScriptJobId,
        itemId: ScriptJobItemId,
        attempt: Int,
        status: ScriptJobItemStatus,
        results: List<ScriptExecutionResult>,
        error: String? = null,
    )

    suspend fun expireLeasedRunningItems(
        id: ScriptJobId,
        nowMillis: Long = System.currentTimeMillis(),
        error: String = "script job item lease expired",
    ): List<ScriptJobItem>

    suspend fun find(id: ScriptJobId): ScriptJob?

    suspend fun listRecoverableJobs(limit: Int = 100): List<ScriptJob>

    suspend fun listItems(id: ScriptJobId, query: ScriptJobItemQuery = ScriptJobItemQuery()): ScriptJobItemPage

    suspend fun findItem(id: ScriptJobId, itemId: ScriptJobItemId): ScriptJobItem?
}

/**
 * In-memory repository for tests and local development.
 *
 * It preserves the same state transition semantics as durable implementations, but it is not restart-safe and should
 * not be used for production GM operations.
 */
class InMemoryScriptJobRepository : ScriptJobRepository {
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

    override suspend fun claimPendingItems(
        id: ScriptJobId,
        workerId: String,
        limit: Int,
        leaseUntilMillis: Long,
        nowMillis: Long,
    ): List<ScriptJobItem> {
        require(workerId.isNotBlank()) { "script job worker id must not be blank" }
        require(limit > 0) { "script job claim limit must be positive" }
        require(leaseUntilMillis > nowMillis) { "script job item lease must be in the future" }
        return mutex.withLock {
            val stored = jobs[id] ?: return@withLock emptyList()
            stored.items.values
                .asSequence()
                .filter { it.status == ScriptJobItemStatus.Pending }
                .filter { it.leaseUntilMillis == null || it.leaseUntilMillis <= nowMillis }
                .take(limit)
                .map { item ->
                    val claimed = item.copy(
                        leaseOwner = workerId,
                        leaseUntilMillis = leaseUntilMillis,
                        updatedAtMillis = nowMillis,
                    )
                    stored.items[item.id] = claimed
                    claimed
                }
                .toList()
        }
    }

    override suspend fun markItemRunning(
        jobId: ScriptJobId,
        itemId: ScriptJobItemId,
        attempt: Int,
        command: ScriptExecutionCommand,
        leaseOwner: String,
        leaseUntilMillis: Long,
    ) {
        require(attempt > 0) { "script job item attempt must be greater than 0" }
        require(leaseOwner.isNotBlank()) { "script job item lease owner must not be blank" }
        update(jobId) { stored, now ->
            val item = stored.item(itemId)
            require(attempt == item.attempts.size + 1) {
                "script job item $itemId expected attempt ${item.attempts.size + 1}, got $attempt"
            }
            require(item.status == ScriptJobItemStatus.Pending || item.status == ScriptJobItemStatus.Failed) {
                "script job item $itemId cannot start from status ${item.status}"
            }
            require(leaseUntilMillis > now) { "script job item lease must be in the future" }
            stored.items[itemId] = item.copy(
                status = ScriptJobItemStatus.Running,
                attempts = item.attempts + ScriptJobItemAttempt(
                    attempt = attempt,
                    command = command,
                    status = ScriptJobItemStatus.Running,
                    startedAtMillis = now,
                ),
                leaseOwner = leaseOwner,
                leaseUntilMillis = leaseUntilMillis,
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
                leaseOwner = null,
                leaseUntilMillis = null,
                updatedAtMillis = now,
            )
            stored.refresh(now)
        }
    }

    override suspend fun expireLeasedRunningItems(
        id: ScriptJobId,
        nowMillis: Long,
        error: String,
    ): List<ScriptJobItem> {
        require(error.isNotBlank()) { "script job item expiration error must not be blank" }
        return mutex.withLock {
            val stored = jobs[id] ?: return@withLock emptyList()
            val expired = stored.items.values
                .filter { it.status == ScriptJobItemStatus.Running }
                .filter { it.leaseUntilMillis != null && it.leaseUntilMillis <= nowMillis }
                .map { item ->
                    val attempt = item.attempts.lastOrNull()
                    val attempts = if (attempt == null) {
                        item.attempts
                    } else {
                        item.attempts.dropLast(1) + attempt.copy(
                            status = ScriptJobItemStatus.Failed,
                            error = error,
                            finishedAtMillis = nowMillis,
                        )
                    }
                    val updated = item.copy(
                        status = ScriptJobItemStatus.Failed,
                        attempts = attempts,
                        leaseOwner = null,
                        leaseUntilMillis = null,
                        updatedAtMillis = nowMillis,
                    )
                    stored.items[item.id] = updated
                    updated
                }
            if (expired.isNotEmpty()) {
                stored.refresh(nowMillis)
            }
            expired
        }
    }

    override suspend fun find(id: ScriptJobId): ScriptJob? {
        return mutex.withLock { jobs[id]?.job }
    }

    override suspend fun listRecoverableJobs(limit: Int): List<ScriptJob> {
        require(limit > 0) { "script job recoverable job limit must be positive" }
        return mutex.withLock {
            jobs.values
                .asSequence()
                .map { it.job }
                .filter { it.status == ScriptJobStatus.Pending || it.status == ScriptJobStatus.Running }
                .take(limit)
                .toList()
        }
    }

    override suspend fun listItems(id: ScriptJobId, query: ScriptJobItemQuery): ScriptJobItemPage {
        return mutex.withLock {
            val values = jobs[id]
                ?.items
                ?.values
                ?.filter { query.status == null || it.status == query.status }
                ?: emptyList()
            ScriptJobItemPage(
                items = values.drop(query.offset).take(query.limit),
                offset = query.offset,
                limit = query.limit,
                total = values.size.toLong(),
            )
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
                completedItems = completed,
                failedItems = failed,
                updatedAtMillis = now,
            )
        }
    }
}
