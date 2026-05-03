package io.github.realmlabs.asteria.script

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface ScriptRuntime {
    suspend fun execute(
        command: ScriptExecutionCommand,
        timeout: Duration = 3.seconds,
    ): ScriptExecutionResult

    suspend fun executeAll(
        command: ScriptExecutionCommand,
        timeout: Duration = 3.seconds,
    ): ScriptExecutionBatchResult

    fun dispatch(command: ScriptExecutionCommand)
}
