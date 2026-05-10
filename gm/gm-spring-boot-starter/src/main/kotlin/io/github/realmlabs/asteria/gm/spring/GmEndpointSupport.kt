package io.github.realmlabs.asteria.gm.spring

import io.github.realmlabs.asteria.gm.core.*
import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory

/**
 * Request context passed through GM HTTP operations.
 */
data class GmOperationContext(
    val principal: GmPrincipal,
    val request: GmRequestContext,
)

/**
 * Shared support for secured GM HTTP endpoints.
 */
class GmEndpointSupport(
    private val principalResolver: GmPrincipalResolver,
    private val authorizationPolicy: GmAuthorizationPolicy,
    private val auditSink: GmAuditSink,
    private val metrics: Metrics = NoopMetrics,
) {
    private val logger = LoggerFactory.getLogger(GmEndpointSupport::class.java)

    suspend fun <T> execute(
        request: HttpServletRequest,
        operation: GmOperation,
        block: suspend (GmOperationContext) -> T,
    ): T {
        val tags = MetricTags.of(
            "action" to operation.action.value,
            "resource" to operation.resource.type,
            "risk" to operation.risk.name,
        )
        metrics.counter("asteria.gm.http.request.total", tags).increment()
        val start = System.nanoTime()
        return try {
            val httpContext = GmHttpRequestContext.from(request)
            val requestContext = GmRequestContext(
                requestId = httpContext.requestId,
                remoteAddress = httpContext.remoteAddress,
            )
            val principal = principalResolver.resolve(httpContext)
                ?: run {
                    metrics.counter("asteria.gm.http.request.unauthenticated.total", tags).increment()
                    throw GmAuthenticationRequiredException("GM authentication required")
                }
            val context = GmOperationContext(
                principal = principal,
                request = requestContext,
            )
            val authorization = authorizationPolicy.authorize(
                GmAuthorizationRequest(
                    principal = principal,
                    operation = operation,
                    request = requestContext,
                ),
            )
            if (authorization is GmAuthorizationDecision.Denied) {
                metrics.counter("asteria.gm.http.request.denied.total", tags).increment()
                audit(context, operation, false, authorization.reason)
                throw GmAccessDeniedException(authorization.reason)
            }
            runCatching {
                block(context)
            }.onSuccess {
                audit(context, operation, true, null)
            }.onFailure { error ->
                metrics.counter("asteria.gm.http.request.failed.total", tags).increment()
                logger.warn("GM HTTP operation failed: action={}", operation.action.value, error)
                audit(context, operation, false, error.message)
            }.getOrThrow()
        } finally {
            metrics.timer("asteria.gm.http.request.duration", tags)
                .record((System.nanoTime() - start) / 1_000_000)
        }
    }

    private suspend fun audit(
        context: GmOperationContext,
        operation: GmOperation,
        success: Boolean,
        message: String?,
    ) {
        auditSink.record(
            GmAuditEvent(
                operatorId = context.principal.id,
                operation = operation,
                request = context.request,
                success = success,
                message = message,
            ),
        )
    }
}
