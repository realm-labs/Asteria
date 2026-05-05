package io.github.realmlabs.asteria.script

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Executes script commands against one or more runtime targets.
 *
 * [execute] expects a single effective result and may reject fan-out targets. [executeAll] collects all results that the
 * runtime can observe before [timeout]; some integrations may return a partial or empty batch when targets do not
 * respond. [dispatch] is fire-and-forget and does not provide completion or failure feedback.
 */
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
