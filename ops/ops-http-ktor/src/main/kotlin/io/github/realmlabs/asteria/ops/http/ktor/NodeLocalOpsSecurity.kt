package io.github.realmlabs.asteria.ops.http.ktor

import io.ktor.http.*
import io.ktor.server.application.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * HTTP headers used to bind node-local OPS calls to an operator and reason.
 */
object OpsHeaders {
    const val OPERATOR: String = "X-Asteria-Operator"
    const val REASON: String = "X-Asteria-Reason"
    const val TICKET: String = "X-Asteria-Ticket"
    const val SOURCE: String = "X-Asteria-Source"
}

/**
 * Authenticated caller context for node-local OPS requests.
 */
data class NodeLocalOpsPrincipal(
    val id: String,
    val source: String = "node-local-http",
    val reason: String? = null,
    val ticket: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

/**
 * Audit event recorded around each OPS action.
 */
data class NodeLocalOpsAuditEvent(
    val action: String,
    val principal: NodeLocalOpsPrincipal,
    val attributes: Map<String, String> = emptyMap(),
    val succeeded: Boolean? = null,
    val error: String? = null,
    val occurredAtMillis: Long = System.currentTimeMillis(),
)

/**
 * Receives OPS HTTP audit events after authentication and before errors are returned.
 */
fun interface NodeLocalOpsAuditSink {
    suspend fun record(event: NodeLocalOpsAuditEvent)
}

object NoopNodeLocalOpsAuditSink : NodeLocalOpsAuditSink {
    override suspend fun record(event: NodeLocalOpsAuditEvent) {
    }
}

fun interface NodeLocalOpsTokenValidator {
    fun validate(token: String?): Boolean
}

/**
 * Token validator for explicitly unsecured local deployments.
 */
object AllowAllNodeLocalOpsTokenValidator : NodeLocalOpsTokenValidator {
    override fun validate(token: String?): Boolean = true
}

/**
 * Constant-time bearer token validator backed by an inline token or token file.
 */
class StaticNodeLocalOpsTokenValidator private constructor(
    private val expected: ByteArray,
) : NodeLocalOpsTokenValidator {
    override fun validate(token: String?): Boolean {
        val actual = token?.trim()?.toByteArray(Charsets.UTF_8) ?: return false
        return MessageDigest.isEqual(expected, actual)
    }

    companion object {
        fun from(
            token: String?,
            tokenFile: Path?,
        ): StaticNodeLocalOpsTokenValidator {
            val resolved = token ?: tokenFile?.let { Files.readString(it).trim() }
            require(!resolved.isNullOrBlank()) {
                "node-local ops token or tokenFile must be configured when token is required"
            }
            return StaticNodeLocalOpsTokenValidator(resolved.toByteArray(Charsets.UTF_8))
        }
    }
}

/**
 * Exception mapped by the OPS HTTP module to an explicit response status.
 */
class NodeLocalOpsHttpException(
    val status: HttpStatusCode,
    override val message: String,
) : RuntimeException(message)

/**
 * Authenticates a Ktor call and extracts the operator context from OPS headers.
 */
fun ApplicationCall.authenticateNodeLocalOps(
    options: NodeLocalOpsHttpOptions,
    tokenValidator: NodeLocalOpsTokenValidator,
): NodeLocalOpsPrincipal {
    val token = bearerToken()
    if (!tokenValidator.validate(token)) {
        throw NodeLocalOpsHttpException(HttpStatusCode.Unauthorized, "invalid node-local ops token")
    }
    val operator = request.headers[OpsHeaders.OPERATOR]?.trim()
    if (options.requireOperator && operator.isNullOrBlank()) {
        throw NodeLocalOpsHttpException(HttpStatusCode.Forbidden, "node-local ops operator is required")
    }
    return NodeLocalOpsPrincipal(
        id = operator?.takeIf { it.isNotBlank() } ?: "node-local",
        source = request.headers[OpsHeaders.SOURCE]?.takeIf { it.isNotBlank() } ?: "node-local-http",
        reason = request.headers[OpsHeaders.REASON]?.takeIf { it.isNotBlank() },
        ticket = request.headers[OpsHeaders.TICKET]?.takeIf { it.isNotBlank() },
        attributes = buildMap {
            request.headers[OpsHeaders.TICKET]?.takeIf { it.isNotBlank() }?.let { put("ticket", it) }
        },
    )
}

private fun ApplicationCall.bearerToken(): String? {
    val authorization = request.headers[HttpHeaders.Authorization] ?: return null
    val prefix = "Bearer "
    return authorization.takeIf { it.startsWith(prefix, ignoreCase = true) }?.substring(prefix.length)
}
