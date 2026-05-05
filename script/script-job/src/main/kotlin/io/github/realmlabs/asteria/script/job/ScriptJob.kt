package io.github.realmlabs.asteria.script.job

import io.github.realmlabs.asteria.script.ScriptExecutionCommand
import io.github.realmlabs.asteria.script.ScriptExecutionResult
import io.github.realmlabs.asteria.script.ScriptTarget

@JvmInline
value class ScriptJobId(val value: String) {
    init {
        require(value.isNotBlank()) { "script job id must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class ScriptJobItemId(val value: String) {
    init {
        require(value.isNotBlank()) { "script job item id must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Aggregate status derived from all items in a job.
 *
 * `PartialFailed` means at least one item completed and at least one item failed. `Cancelled` means all terminal
 * non-completed items were cancelled. Pending or running items keep the job in `Running` once work has started.
 */
enum class ScriptJobStatus {
    Pending,
    Running,
    Completed,
    PartialFailed,
    Failed,
    Cancelled,
}

/**
 * Status of one target item.
 *
 * Items normally move `Pending -> Running -> Completed/Failed/Cancelled`. Running cancellation is cooperative: the
 * repository records the request, and the runtime or recovery path later turns the item terminal.
 */
enum class ScriptJobItemStatus {
    Pending,
    Running,
    Completed,
    Failed,
    Cancelled,
}

/**
 * Stored script job summary.
 *
 * [totalItems] is fixed at creation from the expanded target list. Completed, failed, and cancelled counters are
 * derived from item statuses whenever the repository updates the job.
 */
data class ScriptJob(
    val id: ScriptJobId,
    val command: ScriptExecutionCommand,
    val status: ScriptJobStatus = ScriptJobStatus.Pending,
    val attempt: Int = 1,
    val totalItems: Int = 0,
    val completedItems: Int = 0,
    val failedItems: Int = 0,
    val cancelledItems: Int = 0,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis,
) {
    init {
        require(attempt > 0) { "script job attempt must be greater than 0" }
        require(totalItems >= 0) { "script job total items must not be negative" }
        require(completedItems >= 0) { "script job completed items must not be negative" }
        require(failedItems >= 0) { "script job failed items must not be negative" }
        require(cancelledItems >= 0) { "script job cancelled items must not be negative" }
        require(completedItems + failedItems + cancelledItems <= totalItems) {
            "script job item counters exceed total items"
        }
    }
}

/**
 * Stored execution unit for one effective target.
 *
 * [attempts] records every run attempt. [results] is populated only on terminal item completion/failure; while running,
 * ownership is represented by [leaseOwner] and [leaseUntilMillis].
 */
data class ScriptJobItem(
    val id: ScriptJobItemId,
    val jobId: ScriptJobId,
    val target: ScriptTarget,
    val status: ScriptJobItemStatus = ScriptJobItemStatus.Pending,
    val results: List<ScriptExecutionResult> = emptyList(),
    val attempts: List<ScriptJobItemAttempt> = emptyList(),
    val leaseOwner: String? = null,
    val leaseUntilMillis: Long? = null,
    val cancelRequestedBy: String? = null,
    val cancelReason: String? = null,
    val cancelRequestedAtMillis: Long? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis,
) {
    init {
        leaseOwner?.let { require(it.isNotBlank()) { "script job item lease owner must not be blank" } }
        leaseUntilMillis?.let { require(it >= 0) { "script job item lease time must not be negative" } }
        cancelRequestedBy?.let { require(it.isNotBlank()) { "script job item cancel requester must not be blank" } }
        cancelReason?.let { require(it.isNotBlank()) { "script job item cancel reason must not be blank" } }
        cancelRequestedAtMillis?.let { require(it >= 0) { "script job item cancel time must not be negative" } }
    }
}

data class ScriptJobQuery(
    val status: ScriptJobStatus? = null,
    val requester: String? = null,
    val offset: Int = 0,
    val limit: Int = 100,
) {
    init {
        requester?.let { require(it.isNotBlank()) { "script job query requester must not be blank" } }
        require(offset >= 0) { "script job query offset must not be negative" }
        require(limit > 0) { "script job query limit must be positive" }
    }
}

data class ScriptJobPage(
    val jobs: List<ScriptJob>,
    val offset: Int,
    val limit: Int,
    val total: Long,
) {
    val nextOffset: Int? = if (offset + jobs.size < total) offset + jobs.size else null
}

data class ScriptJobCancellation(
    val requestedBy: String? = null,
    val reason: String? = null,
) {
    init {
        requestedBy?.let { require(it.isNotBlank()) { "script job cancellation requester must not be blank" } }
        reason?.let { require(it.isNotBlank()) { "script job cancellation reason must not be blank" } }
    }
}

data class ScriptJobItemQuery(
    val status: ScriptJobItemStatus? = null,
    val offset: Int = 0,
    val limit: Int = 100,
) {
    init {
        require(offset >= 0) { "script job item query offset must not be negative" }
        require(limit > 0) { "script job item query limit must be positive" }
    }
}

data class ScriptJobItemPage(
    val items: List<ScriptJobItem>,
    val offset: Int,
    val limit: Int,
    val total: Long,
) {
    val nextOffset: Int? = if (offset + items.size < total) offset + items.size else null
}

/**
 * One execution attempt for a job item.
 *
 * Running attempts have no [finishedAtMillis]. Terminal attempts hold the script results or an [error] recorded by
 * runtime failure, cancellation, or lease expiration.
 */
data class ScriptJobItemAttempt(
    val attempt: Int,
    val command: ScriptExecutionCommand,
    val status: ScriptJobItemStatus = ScriptJobItemStatus.Running,
    val results: List<ScriptExecutionResult> = emptyList(),
    val error: String? = null,
    val startedAtMillis: Long = System.currentTimeMillis(),
    val finishedAtMillis: Long? = null,
) {
    init {
        require(attempt > 0) { "script job item attempt must be greater than 0" }
        require(status != ScriptJobItemStatus.Pending) { "script job item attempt cannot be pending" }
        error?.let { require(it.isNotBlank()) { "script job item attempt error must not be blank" } }
    }
}

data class ScriptJobSubmitRequest(
    val id: ScriptJobId,
    val command: ScriptExecutionCommand,
)
