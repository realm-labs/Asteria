package io.github.mikai233.asteria.script

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface ScriptRuntime {
    suspend fun execute(
        command: ScriptExecutionCommand,
        timeout: Duration = 3.seconds,
    ): ScriptExecutionResult

    fun dispatch(command: ScriptExecutionCommand)
}
