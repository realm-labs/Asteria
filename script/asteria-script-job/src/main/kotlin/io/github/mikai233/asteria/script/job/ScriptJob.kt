package io.github.mikai233.asteria.script.job

import io.github.mikai233.asteria.script.ScriptExecutionCommand
import io.github.mikai233.asteria.script.ScriptExecutionResult
import io.github.mikai233.asteria.script.ScriptTarget

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

enum class ScriptJobStatus {
    Pending,
    Running,
    Completed,
    PartialFailed,
    Failed,
}

enum class ScriptJobItemStatus {
    Pending,
    Running,
    Completed,
    Failed,
}

data class ScriptJob(
    val id: ScriptJobId,
    val command: ScriptExecutionCommand,
    val status: ScriptJobStatus = ScriptJobStatus.Pending,
    val attempt: Int = 1,
    val totalItems: Int = 0,
    val completedItems: Int = 0,
    val failedItems: Int = 0,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis,
) {
    init {
        require(attempt > 0) { "script job attempt must be greater than 0" }
        require(totalItems >= 0) { "script job total items must not be negative" }
        require(completedItems >= 0) { "script job completed items must not be negative" }
        require(failedItems >= 0) { "script job failed items must not be negative" }
        require(completedItems + failedItems <= totalItems) { "script job item counters exceed total items" }
    }
}

data class ScriptJobItem(
    val id: ScriptJobItemId,
    val jobId: ScriptJobId,
    val target: ScriptTarget,
    val status: ScriptJobItemStatus = ScriptJobItemStatus.Pending,
    val results: List<ScriptExecutionResult> = emptyList(),
    val attempts: List<ScriptJobItemAttempt> = emptyList(),
    val leaseOwner: String? = null,
    val leaseUntilMillis: Long? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis,
) {
    init {
        leaseOwner?.let { require(it.isNotBlank()) { "script job item lease owner must not be blank" } }
        leaseUntilMillis?.let { require(it >= 0) { "script job item lease time must not be negative" } }
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
