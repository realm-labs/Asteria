package io.github.mikai233.asteria.gm.spring

import io.github.mikai233.asteria.gm.core.GmPermissionKey
import io.github.mikai233.asteria.gm.core.GmPrincipal

/**
 * Resolves the current GM operator from an HTTP request.
 *
 * Production applications usually implement this by adapting Spring Security, an internal SSO token, or a trusted
 * gateway identity. Returning `null` means the request is unauthenticated.
 */
fun interface GmPrincipalResolver {
    fun resolve(request: GmHttpRequestContext): GmPrincipal?
}

/**
 * Safe default resolver used when an application has not installed authentication yet.
 */
object NoopGmPrincipalResolver : GmPrincipalResolver {
    override fun resolve(request: GmHttpRequestContext): GmPrincipal? = null
}

/**
 * Resolves GM principals from trusted HTTP headers.
 *
 * This resolver is intended for local development or deployments where an internal gateway has already authenticated
 * the request and strips untrusted inbound identity headers. It is not enabled automatically.
 */
class HeaderGmPrincipalResolver(
    private val idHeader: String = "X-GM-User",
    private val displayNameHeader: String = "X-GM-Display-Name",
    private val rolesHeader: String = "X-GM-Roles",
    private val permissionsHeader: String = "X-GM-Permissions",
    private val scopeHeaderPrefix: String = "X-GM-Scope-",
) : GmPrincipalResolver {
    override fun resolve(request: GmHttpRequestContext): GmPrincipal? {
        val id = request.firstHeader(idHeader)?.takeIf { it.isNotBlank() } ?: return null
        return GmPrincipal(
            id = id,
            displayName = request.firstHeader(displayNameHeader)?.takeIf { it.isNotBlank() },
            roles = request.firstHeader(rolesHeader).csvValues(),
            permissions = request.firstHeader(permissionsHeader)
                .csvValues()
                .mapTo(linkedSetOf(), ::GmPermissionKey),
            scopeValues = request.headers
                .filterKeys { it.startsWith(scopeHeaderPrefix.lowercase()) }
                .mapKeys { (key, _) -> key.removePrefix(scopeHeaderPrefix.lowercase()) }
                .mapValues { (_, values) -> values.flatMap { it.csvValues() }.toSet() },
        )
    }

    private fun String?.csvValues(): Set<String> {
        return this
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toCollection(linkedSetOf())
            ?: emptySet()
    }
}
