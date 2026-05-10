package io.github.realmlabs.asteria.gm.core

/**
 * Stable action key for one GM operation, such as `script.submit` or `cluster.node.down`.
 */
@JvmInline
value class GmAction(val value: String) {
    init {
        require(value.isNotBlank()) { "GM action must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Resource affected by a GM operation.
 */
data class GmResource(
    val type: String,
    val id: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(type.isNotBlank()) { "GM resource type must not be blank" }
        id?.let { require(it.isNotBlank()) { "GM resource id must not be blank" } }
        attributes.forEach { (key, value) ->
            require(key.isNotBlank()) { "GM resource attribute key must not be blank" }
            require(value.isNotBlank()) { "GM resource attribute value must not be blank" }
        }
    }

    companion object {
        val System: GmResource = GmResource("system")
    }
}

/**
 * Risk level attached to a GM operation.
 */
enum class GmRiskLevel {
    Normal,
    High,
}

/**
 * Complete framework-facing description of a GM operation.
 */
data class GmOperation(
    val action: GmAction,
    val resource: GmResource = GmResource.System,
    val risk: GmRiskLevel = GmRiskLevel.Normal,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        attributes.forEach { (key, value) ->
            require(key.isNotBlank()) { "GM operation attribute key must not be blank" }
            require(value.isNotBlank()) { "GM operation attribute value must not be blank" }
        }
    }
}

/**
 * Authenticated GM operator.
 */
data class GmPrincipal(
    val id: String,
    val displayName: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(id.isNotBlank()) { "GM principal id must not be blank" }
        displayName?.let { require(it.isNotBlank()) { "GM principal display name must not be blank" } }
        attributes.forEach { (key, value) ->
            require(key.isNotBlank()) { "GM principal attribute key must not be blank" }
            require(value.isNotBlank()) { "GM principal attribute value must not be blank" }
        }
    }
}

/**
 * Transport-level request context available to authorization policies.
 */
data class GmRequestContext(
    val requestId: String? = null,
    val remoteAddress: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        requestId?.let { require(it.isNotBlank()) { "GM request id must not be blank" } }
        remoteAddress?.let { require(it.isNotBlank()) { "GM request remote address must not be blank" } }
        attributes.forEach { (key, value) ->
            require(key.isNotBlank()) { "GM request attribute key must not be blank" }
            require(value.isNotBlank()) { "GM request attribute value must not be blank" }
        }
    }
}

/**
 * Authorization input for a single GM operation.
 */
data class GmAuthorizationRequest(
    val principal: GmPrincipal,
    val operation: GmOperation,
    val request: GmRequestContext = GmRequestContext(),
)

/**
 * Result of a policy decision.
 */
sealed interface GmAuthorizationDecision {
    data object Allowed : GmAuthorizationDecision

    data class Denied(val reason: String) : GmAuthorizationDecision {
        init {
            require(reason.isNotBlank()) { "GM authorization denial reason must not be blank" }
        }
    }
}

/**
 * Decides whether a GM principal can perform an operation.
 */
fun interface GmAuthorizationPolicy {
    suspend fun authorize(request: GmAuthorizationRequest): GmAuthorizationDecision
}

/**
 * Safe default policy for deployments that have not installed an application policy.
 */
object DenyAllGmAuthorizationPolicy : GmAuthorizationPolicy {
    override suspend fun authorize(request: GmAuthorizationRequest): GmAuthorizationDecision {
        return GmAuthorizationDecision.Denied("GM authorization policy is not configured")
    }
}

/**
 * Policy for local development or fully trusted test environments.
 */
object AllowAllGmAuthorizationPolicy : GmAuthorizationPolicy {
    override suspend fun authorize(request: GmAuthorizationRequest): GmAuthorizationDecision {
        return GmAuthorizationDecision.Allowed
    }
}
