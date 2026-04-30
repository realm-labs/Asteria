package io.github.mikai233.asteria.script

enum class ScriptExecutionScope {
    Node,
    Actor,
}

data class ScriptExecutionRequest(
    val executionId: String,
    val target: ScriptTarget,
    val artifact: ScriptArtifact,
    val scope: ScriptExecutionScope,
    val nodeAddress: String? = null,
    val actorPath: String? = null,
) {
    init {
        require(executionId.isNotBlank()) { "script execution id must not be blank" }
    }
}

sealed interface ScriptAuthorization {
    data object Allowed : ScriptAuthorization

    data class Denied(val reason: String) : ScriptAuthorization {
        init {
            require(reason.isNotBlank()) { "script denial reason must not be blank" }
        }
    }
}

fun interface ScriptPolicy {
    suspend fun authorize(request: ScriptExecutionRequest): ScriptAuthorization
}

interface ScriptAuditSink {
    suspend fun started(request: ScriptExecutionRequest) {
    }

    suspend fun completed(request: ScriptExecutionRequest, result: ScriptExecutionResult) {
    }

    suspend fun rejected(request: ScriptExecutionRequest, reason: String) {
    }
}

object NoopScriptAuditSink : ScriptAuditSink

class CompositeScriptAuditSink(
    private val sinks: List<ScriptAuditSink>,
) : ScriptAuditSink {
    override suspend fun started(request: ScriptExecutionRequest) {
        sinks.forEach { it.started(request) }
    }

    override suspend fun completed(request: ScriptExecutionRequest, result: ScriptExecutionResult) {
        sinks.forEach { it.completed(request, result) }
    }

    override suspend fun rejected(request: ScriptExecutionRequest, reason: String) {
        sinks.forEach { it.rejected(request, reason) }
    }
}

class DefaultScriptPolicy(
    private val allowNodeScripts: Boolean = false,
    private val allowActorScripts: Boolean = false,
    private val allowedEngines: Set<String> = emptySet(),
    private val maxArtifactBytes: Int = 1024 * 1024,
) : ScriptPolicy {
    init {
        require(maxArtifactBytes > 0) { "max script artifact bytes must be greater than 0" }
    }

    override suspend fun authorize(request: ScriptExecutionRequest): ScriptAuthorization {
        if (request.artifact.engine !in allowedEngines) {
            return ScriptAuthorization.Denied("script engine ${request.artifact.engine} is not registered")
        }
        if (request.artifact.body.size > maxArtifactBytes) {
            return ScriptAuthorization.Denied("script artifact exceeds max size $maxArtifactBytes")
        }
        return when (request.scope) {
            ScriptExecutionScope.Node -> allow(allowNodeScripts, "node scripts are disabled")
            ScriptExecutionScope.Actor -> allow(allowActorScripts, "actor scripts are disabled")
        }
    }

    private fun allow(allowed: Boolean, reason: String): ScriptAuthorization {
        return if (allowed) ScriptAuthorization.Allowed else ScriptAuthorization.Denied(reason)
    }
}

class ScriptRunner(
    private val executor: ScriptExecutor,
    private val policy: ScriptPolicy,
    private val auditSink: ScriptAuditSink = NoopScriptAuditSink,
) {
    suspend fun execute(
        request: ScriptExecutionRequest,
        context: ScriptContext,
        defaultResult: () -> ScriptExecutionResult,
        failureResult: (Throwable) -> ScriptExecutionResult,
    ): ScriptExecutionResult {
        return when (val authorization = policy.authorize(request)) {
            ScriptAuthorization.Allowed -> executeAuthorized(request, context, defaultResult, failureResult)
            is ScriptAuthorization.Denied -> {
                val result = ScriptExecutionResult(
                    executionId = request.executionId,
                    success = false,
                    target = targetName(request),
                    error = authorization.reason,
                    nodeAddress = request.nodeAddress,
                    actorPath = request.actorPath,
                )
                auditSink.rejected(request, authorization.reason)
                result
            }
        }
    }

    private suspend fun executeAuthorized(
        request: ScriptExecutionRequest,
        context: ScriptContext,
        defaultResult: () -> ScriptExecutionResult,
        failureResult: (Throwable) -> ScriptExecutionResult,
    ): ScriptExecutionResult {
        auditSink.started(request)
        val result = runCatching {
            executor.execute(context) ?: defaultResult()
        }.getOrElse(failureResult)
        auditSink.completed(request, result)
        return result
    }

    private fun targetName(request: ScriptExecutionRequest): String? {
        return when (val target = request.target) {
            ScriptTarget.AllNodes -> "all-nodes"
            is ScriptTarget.ActorPath -> target.path
            is ScriptTarget.Entity -> target.id
            is ScriptTarget.Node -> target.address
            is ScriptTarget.Role -> target.role.value
            is ScriptTarget.Singleton -> target.name.value
        }
    }
}
