package io.github.mikai233.asteria.script.job

import io.github.mikai233.asteria.script.ScriptExecutionCommand
import io.github.mikai233.asteria.script.ScriptExecutionResult

@JvmInline
value class ScriptJobId(val value: String) {
    init {
        require(value.isNotBlank()) { "script job id must not be blank" }
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

data class ScriptJob(
    val id: ScriptJobId,
    val command: ScriptExecutionCommand,
    val status: ScriptJobStatus = ScriptJobStatus.Pending,
    val results: List<ScriptExecutionResult> = emptyList(),
    val attempt: Int = 1,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis,
) {
    init {
        require(attempt > 0) { "script job attempt must be greater than 0" }
    }
}

data class ScriptJobSubmitRequest(
    val id: ScriptJobId,
    val command: ScriptExecutionCommand,
)
