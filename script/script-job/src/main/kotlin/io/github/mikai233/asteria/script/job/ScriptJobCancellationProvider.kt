package io.github.mikai233.asteria.script.job

import io.github.mikai233.asteria.script.NonCancellableScriptToken
import io.github.mikai233.asteria.script.ScriptCancellationProvider
import io.github.mikai233.asteria.script.ScriptCancellationToken
import io.github.mikai233.asteria.script.ScriptExecutionRequest

class ScriptJobCancellationProvider(
    private val repository: ScriptJobRepository,
) : ScriptCancellationProvider {
    override fun token(request: ScriptExecutionRequest): ScriptCancellationToken {
        val jobId = request.metadata.attributes["script.jobId"]?.let(::ScriptJobId)
            ?: return NonCancellableScriptToken
        val itemId = request.metadata.attributes["script.itemId"]?.let(::ScriptJobItemId)
            ?: return NonCancellableScriptToken
        val attempt = request.metadata.attributes["script.attempt"]?.toIntOrNull()
            ?: return NonCancellableScriptToken
        return ScriptJobCancellationToken(repository, jobId, itemId, attempt)
    }
}

private class ScriptJobCancellationToken(
    private val repository: ScriptJobRepository,
    private val jobId: ScriptJobId,
    private val itemId: ScriptJobItemId,
    private val attempt: Int,
) : ScriptCancellationToken {
    override suspend fun isCancellationRequested(): Boolean {
        val item = repository.findItem(jobId, itemId) ?: return true
        return item.status == ScriptJobItemStatus.Cancelled ||
            item.cancelRequestedAtMillis != null ||
            item.attempts.lastOrNull()?.attempt != attempt
    }
}
