package io.github.mikai233.asteria.gm.core

/**
 * Resource scope attached to a GM operation.
 *
 * Scopes are domain-specific dimensions such as `server`, `zone`, `region`, or `channel`. A permission grants the
 * right to perform an action; the scope limits where that action can be performed.
 */
data class GmResourceScope(
    val values: Map<String, String> = emptyMap(),
) {
    init {
        values.forEach { (key, value) ->
            require(key.isNotBlank()) { "GM resource scope key must not be blank" }
            require(value.isNotBlank()) { "GM resource scope value must not be blank" }
        }
    }

    companion object {
        val Empty: GmResourceScope = GmResourceScope()
    }
}

/**
 * Authenticated GM operator.
 *
 * `permissions` should contain resolved permissions after applying the application's role model. `scopeValues` stores
 * allowed values per scope key; the special value `*` means all values for that key.
 */
data class GmPrincipal(
    val id: String,
    val displayName: String? = null,
    val roles: Set<String> = emptySet(),
    val permissions: Set<GmPermissionKey> = emptySet(),
    val scopeValues: Map<String, Set<String>> = emptyMap(),
) {
    init {
        require(id.isNotBlank()) { "GM principal id must not be blank" }
        displayName?.let { require(it.isNotBlank()) { "GM principal display name must not be blank" } }
        roles.forEach { require(it.isNotBlank()) { "GM principal role must not be blank" } }
        scopeValues.forEach { (key, values) ->
            require(key.isNotBlank()) { "GM principal scope key must not be blank" }
            require(values.all { it.isNotBlank() }) { "GM principal scope value must not be blank" }
        }
    }
}

/**
 * Authorization input for a single GM operation.
 */
data class GmAuthorizationRequest(
    val principal: GmPrincipal,
    val permission: GmPermissionKey,
    val scope: GmResourceScope = GmResourceScope.Empty,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        attributes.keys.forEach { require(it.isNotBlank()) { "GM authorization attribute key must not be blank" } }
    }
}

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
 * Evaluates whether a GM principal can perform an operation.
 *
 * Applications can replace this with RBAC, ABAC, approval-flow, or external IAM integrations while keeping the
 * framework-facing permission model unchanged.
 */
fun interface GmPolicyEvaluator {
    fun evaluate(request: GmAuthorizationRequest): GmAuthorizationDecision
}

/**
 * Minimal policy evaluator for local development and simple deployments.
 *
 * It requires the exact permission key and denies scoped requests unless the principal explicitly owns the requested
 * scope value or `*` for that scope key.
 */
class DefaultGmPolicyEvaluator : GmPolicyEvaluator {
    override fun evaluate(request: GmAuthorizationRequest): GmAuthorizationDecision {
        if (request.permission !in request.principal.permissions) {
            return GmAuthorizationDecision.Denied("missing permission ${request.permission}")
        }
        for ((key, value) in request.scope.values) {
            val allowedValues = request.principal.scopeValues[key]
                ?: return GmAuthorizationDecision.Denied("missing scope $key")
            if (value !in allowedValues && "*" !in allowedValues) {
                return GmAuthorizationDecision.Denied("scope $key=$value is not allowed")
            }
        }
        return GmAuthorizationDecision.Allowed
    }
}
