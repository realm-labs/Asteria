package io.github.realmlabs.asteria.script

/**
 * Cooperative cancellation handle exposed to scripts.
 *
 * Script engines cannot safely interrupt arbitrary user code. Long-running scripts should call [ensureActive] at
 * natural checkpoints, such as between rows of a compensation table, so GM cancellation requests can stop work before
 * the script reaches the next actor or data mutation.
 */
interface ScriptCancellationToken {
    suspend fun isCancellationRequested(): Boolean

    suspend fun ensureActive() {
        if (isCancellationRequested()) {
            throw ScriptCancellationException("script execution was cancelled")
        }
    }
}

class ScriptCancellationException(message: String) : RuntimeException(message)

object NonCancellableScriptToken : ScriptCancellationToken {
    override suspend fun isCancellationRequested(): Boolean = false
}

fun interface ScriptCancellationProvider {
    fun token(request: ScriptExecutionRequest): ScriptCancellationToken
}
