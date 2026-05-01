package io.github.mikai233.asteria.gm.spring

import io.github.mikai233.asteria.gm.core.GmAuditEvent
import io.github.mikai233.asteria.gm.core.GmAuditSink
import io.github.mikai233.asteria.gm.core.GmAuthorizationDecision
import io.github.mikai233.asteria.gm.core.GmAuthorizationRequest
import io.github.mikai233.asteria.gm.core.GmPermissionKey
import io.github.mikai233.asteria.gm.core.GmPolicyEvaluator
import io.github.mikai233.asteria.gm.core.GmPrincipal
import io.github.mikai233.asteria.gm.core.GmResourceScope
import jakarta.servlet.http.HttpServletRequest

/**
 * Request context passed through GM HTTP operations.
 */
data class GmOperationContext(
    val principal: GmPrincipal,
    val requestId: String?,
    val remoteAddress: String?,
)

/**
 * Shared support for secured GM HTTP endpoints.
 *
 * Controllers should use this helper instead of repeating principal resolution, policy checks, and audit recording.
 * That keeps high-risk actions such as script execution and actor diagnostics on the same security path.
 */
class GmEndpointSupport(
    private val principalResolver: GmPrincipalResolver,
    private val policyEvaluator: GmPolicyEvaluator,
    private val auditSink: GmAuditSink,
) {
    suspend fun <T> execute(
        request: HttpServletRequest,
        permission: GmPermissionKey,
        action: String,
        scope: GmResourceScope = GmResourceScope.Empty,
        attributes: Map<String, String> = emptyMap(),
        block: suspend (GmOperationContext) -> T,
    ): T {
        val httpContext = GmHttpRequestContext.from(request)
        val principal = principalResolver.resolve(httpContext)
            ?: throw GmAuthenticationRequiredException("GM authentication required")
        val operation = GmOperationContext(
            principal = principal,
            requestId = httpContext.requestId,
            remoteAddress = httpContext.remoteAddress,
        )
        val authorization = policyEvaluator.evaluate(
            GmAuthorizationRequest(
                principal = principal,
                permission = permission,
                scope = scope,
                attributes = attributes,
            ),
        )
        if (authorization is GmAuthorizationDecision.Denied) {
            audit(operation, permission, action, scope, false, authorization.reason, attributes)
            throw GmAccessDeniedException(authorization.reason)
        }
        return runCatching {
            block(operation)
        }.onSuccess {
            audit(operation, permission, action, scope, true, null, attributes)
        }.onFailure { error ->
            audit(operation, permission, action, scope, false, error.message, attributes)
        }.getOrThrow()
    }

    private suspend fun audit(
        context: GmOperationContext,
        permission: GmPermissionKey,
        action: String,
        scope: GmResourceScope,
        success: Boolean,
        message: String?,
        attributes: Map<String, String>,
    ) {
        auditSink.record(
            GmAuditEvent(
                operatorId = context.principal.id,
                permission = permission,
                action = action,
                scope = scope,
                success = success,
                message = message,
                attributes = attributes + listOfNotNull(
                    context.requestId?.let { "requestId" to it },
                    context.remoteAddress?.let { "remoteAddress" to it },
                ),
            ),
        )
    }
}
