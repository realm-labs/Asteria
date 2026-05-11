package io.github.realmlabs.asteria.script

enum class ScriptExecutionScope {
    Node,
    Actor,
}

object ScriptSecurityAttributes {
    const val APPROVED_BY: String = "script.approvedBy"
    const val APPROVAL_TICKET: String = "script.approvalTicket"
    const val PERMISSIONS: String = "script.permissions"
    const val SIGNATURE: String = "script.signature"
    const val TEMPLATE_ID: String = "script.templateId"
}

data class ScriptExecutionRequest(
    val executionId: String,
    val target: ScriptTarget,
    val artifact: ScriptArtifact,
    val scope: ScriptExecutionScope,
    val metadata: ScriptExecutionMetadata = ScriptExecutionMetadata(),
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

fun interface ScriptPermissionAuthorizer {
    suspend fun isAllowed(request: ScriptExecutionRequest, permission: String): Boolean
}

object MetadataScriptPermissionAuthorizer : ScriptPermissionAuthorizer {
    override suspend fun isAllowed(request: ScriptExecutionRequest, permission: String): Boolean {
        return request.metadata.attributes[ScriptSecurityAttributes.PERMISSIONS]
            ?.split(",")
            ?.map { it.trim() }
            ?.any { it == permission }
            ?: false
    }
}

fun interface ScriptSignatureVerifier {
    suspend fun verify(request: ScriptExecutionRequest, signature: String): Boolean
}

fun interface ScriptTemplateCatalog {
    suspend fun exists(templateId: String): Boolean?
}

interface ScriptAuditSink {
    suspend fun started(request: ScriptExecutionRequest) {
    }

    suspend fun alreadyRunning(request: ScriptExecutionRequest) {
    }

    suspend fun replayed(request: ScriptExecutionRequest, result: ScriptExecutionResult) {
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

    override suspend fun alreadyRunning(request: ScriptExecutionRequest) {
        sinks.forEach { it.alreadyRunning(request) }
    }

    override suspend fun replayed(request: ScriptExecutionRequest, result: ScriptExecutionResult) {
        sinks.forEach { it.replayed(request, result) }
    }

    override suspend fun rejected(request: ScriptExecutionRequest, reason: String) {
        sinks.forEach { it.rejected(request, reason) }
    }
}

class DefaultScriptPolicy(
    private val allowNodeScripts: Boolean = false,
    private val allowActorScripts: Boolean = false,
    private val allowedEngines: Set<String> = emptySet(),
    private val allowedTargetTypes: Set<String> = emptySet(),
    private val maxArtifactBytes: Int = 1024 * 1024,
    private val approvalRequired: Boolean = false,
    private val signatureRequired: Boolean = false,
    private val templateRequired: Boolean = false,
    private val enginePermissions: Map<String, String> = emptyMap(),
    private val targetPermissions: Map<String, String> = emptyMap(),
    private val forbiddenApiTokens: Set<String> = DefaultForbiddenApiTokens,
    private val permissionAuthorizer: ScriptPermissionAuthorizer = MetadataScriptPermissionAuthorizer,
    private val signatureVerifier: ScriptSignatureVerifier? = null,
    private val templateCatalog: ScriptTemplateCatalog? = null,
) : ScriptPolicy {
    init {
        require(maxArtifactBytes > 0) { "max script artifact bytes must be greater than 0" }
        allowedEngines.forEach { require(it.isNotBlank()) { "allowed script engine must not be blank" } }
        allowedTargetTypes.forEach { require(it.isNotBlank()) { "allowed script target type must not be blank" } }
        enginePermissions.forEach { (engine, permission) ->
            require(engine.isNotBlank()) { "script engine permission engine must not be blank" }
            require(permission.isNotBlank()) { "script engine permission must not be blank" }
        }
        targetPermissions.forEach { (target, permission) ->
            require(target.isNotBlank()) { "script target permission target must not be blank" }
            require(permission.isNotBlank()) { "script target permission must not be blank" }
        }
        forbiddenApiTokens.forEach { require(it.isNotBlank()) { "forbidden script API token must not be blank" } }
    }

    override suspend fun authorize(request: ScriptExecutionRequest): ScriptAuthorization {
        if (allowedEngines.isNotEmpty() && request.artifact.engine !in allowedEngines) {
            return ScriptAuthorization.Denied("script engine ${request.artifact.engine} is not registered")
        }
        if (request.artifact.body.size > maxArtifactBytes) {
            return ScriptAuthorization.Denied("script artifact exceeds max size $maxArtifactBytes")
        }
        val targetType = request.target.policyType()
        if (allowedTargetTypes.isNotEmpty() && targetType !in allowedTargetTypes) {
            return ScriptAuthorization.Denied("script target $targetType is not allowed")
        }
        forbiddenApiTokens.firstOrNull { token ->
            request.artifact.body.decodeToString().contains(token, ignoreCase = true)
        }?.let { token ->
            return ScriptAuthorization.Denied("script uses forbidden API token $token")
        }
        if (approvalRequired && request.metadata.attributes[ScriptSecurityAttributes.APPROVED_BY].isNullOrBlank()) {
            return ScriptAuthorization.Denied("script approval is required")
        }
        if (templateRequired) {
            val templateId = request.metadata.attributes[ScriptSecurityAttributes.TEMPLATE_ID]
                ?: return ScriptAuthorization.Denied("script template is required")
            if (templateCatalog?.exists(templateId) == false) {
                return ScriptAuthorization.Denied("script template $templateId does not exist")
            }
        }
        if (signatureRequired) {
            val signature = request.metadata.attributes[ScriptSecurityAttributes.SIGNATURE]
                ?: return ScriptAuthorization.Denied("script signature is required")
            val verifier = signatureVerifier
                ?: return ScriptAuthorization.Denied("script signature verifier is not configured")
            if (!verifier.verify(request, signature)) {
                return ScriptAuthorization.Denied("script signature is invalid")
            }
        }
        val requiredPermissions = listOfNotNull(
            enginePermissions[request.artifact.engine],
            targetPermissions[targetType],
        )
        requiredPermissions.firstOrNull { !permissionAuthorizer.isAllowed(request, it) }?.let { permission ->
            return ScriptAuthorization.Denied("script permission $permission is required")
        }
        return when (request.scope) {
            ScriptExecutionScope.Node -> allow(allowNodeScripts, "node scripts are disabled")
            ScriptExecutionScope.Actor -> allow(allowActorScripts, "actor scripts are disabled")
        }
    }

    private fun allow(allowed: Boolean, reason: String): ScriptAuthorization {
        return if (allowed) ScriptAuthorization.Allowed else ScriptAuthorization.Denied(reason)
    }

    companion object {
        val DefaultForbiddenApiTokens: Set<String> = setOf(
            "System.exit",
            "Runtime.getRuntime",
            "ProcessBuilder",
            "java.lang.reflect",
            "Class.forName",
            "setAccessible",
        )
    }
}

class ScriptRunner(
    private val executor: ScriptExecutor,
    private val policy: ScriptPolicy,
    private val auditSink: ScriptAuditSink = NoopScriptAuditSink,
    private val executionStore: ScriptExecutionStore = InMemoryScriptExecutionStore(),
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
        val key = request.executionKey()
        when (val decision = executionStore.tryStart(key)) {
            ScriptExecutionDecision.Started -> Unit
            ScriptExecutionDecision.AlreadyRunning -> {
                auditSink.alreadyRunning(request)
                return ScriptExecutionResult(
                    executionId = request.executionId,
                    success = false,
                    target = targetName(request),
                    error = "script execution is already running",
                    nodeAddress = request.nodeAddress,
                    actorPath = request.actorPath,
                )
            }

            is ScriptExecutionDecision.AlreadyCompleted -> {
                auditSink.replayed(request, decision.result)
                return decision.result
            }
        }
        auditSink.started(request)
        val result = runCatching {
            executor.execute(context)
            defaultResult()
        }.getOrElse(failureResult)
        executionStore.complete(key, result)
        auditSink.completed(request, result)
        return result
    }

    private fun ScriptExecutionRequest.executionKey(): ScriptExecutionKey {
        return ScriptExecutionKey(
            executionId = executionId,
            scope = scope,
            target = targetName(this) ?: scope.name,
            nodeAddress = nodeAddress,
            actorPath = actorPath,
        )
    }

    private fun targetName(request: ScriptExecutionRequest): String? {
        return when (val target = request.target) {
            ScriptTarget.AllNodes -> "all-nodes"
            is ScriptTarget.ActorPath -> target.paths.joinToString(",")
            is ScriptTarget.Entity -> target.ids.joinToString(",")
            is ScriptTarget.Node -> target.addresses.joinToString(",")
            is ScriptTarget.Role -> target.role.value
            is ScriptTarget.Singleton -> target.name.value
        }
    }
}

fun ScriptTarget.policyType(): String {
    return when (this) {
        ScriptTarget.AllNodes -> "all-nodes"
        is ScriptTarget.ActorPath -> "actor-paths"
        is ScriptTarget.Entity -> "entity"
        is ScriptTarget.Node -> "nodes"
        is ScriptTarget.Role -> "role"
        is ScriptTarget.Singleton -> "singleton"
    }
}
