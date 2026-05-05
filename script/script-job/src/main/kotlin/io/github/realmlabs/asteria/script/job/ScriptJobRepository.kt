package io.github.realmlabs.asteria.script.job

import io.github.realmlabs.asteria.script.ScriptExecutionCommand
import io.github.realmlabs.asteria.script.ScriptExecutionResult
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
    /**
     * Creates a job and its full item set.
     *
     * Implementations should reject duplicate job ids and duplicate item ids rather than merging with existing state.
     */
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

    /**
     * Finishes a running attempt if the caller still owns the item lease.
     *
     * Returns `false` for stale finishes: wrong owner, wrong attempt, or an item that is no longer running. If a
     * cancellation was requested while the attempt was running, implementations should store the final item status as
     * [ScriptJobItemStatus.Cancelled] even when [status] is completed or failed.
     */
    suspend fun markItemFinished(
        jobId: ScriptJobId,
        itemId: ScriptJobItemId,
        attempt: Int,
        leaseOwner: String,
        status: ScriptJobItemStatus,
        results: List<ScriptExecutionResult>,
        error: String? = null,
    ): Boolean

    /**
     * Extends the running item lease.
     *
     * Returns `false` when the item no longer exists, is not running, has a different attempt, or is leased by another
     * worker. Callers must then stop executing the item.
     */
    suspend fun renewRunningItemLease(
        jobId: ScriptJobId,
        itemId: ScriptJobItemId,
        attempt: Int,
        leaseOwner: String,
        leaseUntilMillis: Long,
    ): Boolean

    /**
     * Marks expired running leases terminal and returns the items changed by this call.
     *
     * Expired items become failed unless a cancellation was already requested, in which case they become cancelled.
     * Recovery loops call this before claiming more work.
     */
    suspend fun expireLeasedRunningItems(
        id: ScriptJobId,
        nowMillis: Long = System.currentTimeMillis(),
        error: String = "script job item lease expired",
    ): List<ScriptJobItem>

    suspend fun find(id: ScriptJobId): ScriptJob?

    suspend fun listJobs(query: ScriptJobQuery = ScriptJobQuery()): ScriptJobPage

    suspend fun listRecoverableJobs(limit: Int = 100): List<ScriptJob>

    suspend fun listItems(id: ScriptJobId, query: ScriptJobItemQuery = ScriptJobItemQuery()): ScriptJobItemPage

    suspend fun findItem(id: ScriptJobId, itemId: ScriptJobItemId): ScriptJobItem?

    /**
     * Marks all non-terminal items in the job for cancellation.
     *
     * Pending items become cancelled immediately. Running items keep executing until their runtime observes
     * cancellation or their lease expires.
     */
    suspend fun cancelJob(id: ScriptJobId, cancellation: ScriptJobCancellation = ScriptJobCancellation()): ScriptJob?

    /**
     * Marks one item for cancellation using the same pending/running semantics as [cancelJob].
     */
    suspend fun cancelItem(
        id: ScriptJobId,
        itemId: ScriptJobItemId,
        cancellation: ScriptJobCancellation = ScriptJobCancellation(),
    ): ScriptJobItem?
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
            item.leaseOwner?.let {
                require(it == leaseOwner) { "script job item $itemId is leased by another worker" }
            }
            item.leaseUntilMillis?.let {
                require(it > now) { "script job item $itemId lease already expired" }
            }
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
        leaseOwner: String,
        status: ScriptJobItemStatus,
        results: List<ScriptExecutionResult>,
        error: String?,
    ): Boolean {
        require(status == ScriptJobItemStatus.Completed || status == ScriptJobItemStatus.Failed) {
            "script job item finish status must be terminal"
        }
        require(leaseOwner.isNotBlank()) { "script job item lease owner must not be blank" }
        return update(jobId) { stored, now ->
            val item = stored.item(itemId)
            if (
                item.status != ScriptJobItemStatus.Running ||
                item.leaseOwner != leaseOwner ||
                item.attempts.lastOrNull()?.attempt != attempt
            ) {
                return@update false
            }
            val finalStatus = item.finalStatus(status)
            val updatedAttempt = item.attempts.last().copy(
                status = finalStatus,
                results = results,
                error = error,
                finishedAtMillis = now,
            )
            stored.items[itemId] = item.copy(
                status = finalStatus,
                results = results,
                attempts = item.attempts.dropLast(1) + updatedAttempt,
                leaseOwner = null,
                leaseUntilMillis = null,
                updatedAtMillis = now,
            )
            stored.refresh(now)
            true
        }
    }

    override suspend fun renewRunningItemLease(
        jobId: ScriptJobId,
        itemId: ScriptJobItemId,
        attempt: Int,
        leaseOwner: String,
        leaseUntilMillis: Long,
    ): Boolean {
        require(attempt > 0) { "script job item attempt must be greater than 0" }
        require(leaseOwner.isNotBlank()) { "script job item lease owner must not be blank" }
        return mutex.withLock {
            val stored = jobs[jobId] ?: return@withLock false
            val item = stored.items[itemId] ?: return@withLock false
            val now = System.currentTimeMillis()
            require(leaseUntilMillis > now) { "script job item lease must be in the future" }
            if (
                item.status != ScriptJobItemStatus.Running ||
                item.attempts.lastOrNull()?.attempt != attempt ||
                item.leaseOwner != leaseOwner
            ) {
                return@withLock false
            }
            stored.items[itemId] = item.copy(
                leaseUntilMillis = leaseUntilMillis,
                updatedAtMillis = now,
            )
            true
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
                    val finalStatus = item.expiredStatus()
                    val attempts = if (attempt == null) {
                        item.attempts
                    } else {
                        item.attempts.dropLast(1) + attempt.copy(
                            status = finalStatus,
                            error = if (finalStatus == ScriptJobItemStatus.Cancelled) {
                                item.cancelReason ?: "script job item cancelled"
                            } else {
                                error
                            },
                            finishedAtMillis = nowMillis,
                        )
                    }
                    val updated = item.copy(
                        status = finalStatus,
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

    override suspend fun listJobs(query: ScriptJobQuery): ScriptJobPage {
        return mutex.withLock {
            val values = jobs.values
                .asSequence()
                .map { it.job }
                .filter { query.status == null || it.status == query.status }
                .filter { query.requester == null || it.command.metadata.requester == query.requester }
                .toList()
            ScriptJobPage(
                jobs = values.drop(query.offset).take(query.limit),
                offset = query.offset,
                limit = query.limit,
                total = values.size.toLong(),
            )
        }
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

    override suspend fun cancelJob(id: ScriptJobId, cancellation: ScriptJobCancellation): ScriptJob? {
        return mutex.withLock {
            val stored = jobs[id] ?: return@withLock null
            val now = System.currentTimeMillis()
            stored.items.values.forEach { item ->
                stored.items[item.id] = item.cancel(cancellation, now)
            }
            stored.refresh(now)
            stored.job
        }
    }

    override suspend fun cancelItem(
        id: ScriptJobId,
        itemId: ScriptJobItemId,
        cancellation: ScriptJobCancellation,
    ): ScriptJobItem? {
        return mutex.withLock {
            val stored = jobs[id] ?: return@withLock null
            val item = stored.items[itemId] ?: return@withLock null
            val now = System.currentTimeMillis()
            val updated = item.cancel(cancellation, now)
            stored.items[itemId] = updated
            stored.refresh(now)
            updated
        }
    }

    private suspend fun <T> update(
        id: ScriptJobId,
        block: (StoredScriptJob, Long) -> T,
    ): T {
        return mutex.withLock {
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
            val cancelled = values.count { it.status == ScriptJobItemStatus.Cancelled }
            val running = values.any { it.status == ScriptJobItemStatus.Running }
            val pending = values.any { it.status == ScriptJobItemStatus.Pending }
            val status = when {
                running || pending -> ScriptJobStatus.Running
                cancelled > 0 && failed == 0 -> ScriptJobStatus.Cancelled
                failed == 0 -> ScriptJobStatus.Completed
                completed == 0 -> ScriptJobStatus.Failed
                else -> ScriptJobStatus.PartialFailed
            }
            job = job.copy(
                status = status,
                completedItems = completed,
                failedItems = failed,
                cancelledItems = cancelled,
                updatedAtMillis = now,
            )
        }
    }
}

private fun ScriptJobItem.cancel(cancellation: ScriptJobCancellation, now: Long): ScriptJobItem {
    return when (status) {
        ScriptJobItemStatus.Pending -> copy(
            status = ScriptJobItemStatus.Cancelled,
            leaseOwner = null,
            leaseUntilMillis = null,
            cancelRequestedBy = cancellation.requestedBy,
            cancelReason = cancellation.reason,
            cancelRequestedAtMillis = now,
            updatedAtMillis = now,
        )

        ScriptJobItemStatus.Running -> copy(
            cancelRequestedBy = cancellation.requestedBy,
            cancelReason = cancellation.reason,
            cancelRequestedAtMillis = cancelRequestedAtMillis ?: now,
            updatedAtMillis = now,
        )

        ScriptJobItemStatus.Completed,
        ScriptJobItemStatus.Failed,
        ScriptJobItemStatus.Cancelled,
            -> this
    }
}

private fun ScriptJobItem.finalStatus(status: ScriptJobItemStatus): ScriptJobItemStatus {
    return if (cancelRequestedAtMillis == null) status else ScriptJobItemStatus.Cancelled
}

private fun ScriptJobItem.expiredStatus(): ScriptJobItemStatus {
    return if (cancelRequestedAtMillis == null) ScriptJobItemStatus.Failed else ScriptJobItemStatus.Cancelled
}
