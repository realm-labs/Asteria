package io.github.realmlabs.asteria.gm.spring

import io.github.realmlabs.asteria.gm.core.GmPrincipal

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
    private val attributeHeaderPrefix: String = "X-GM-Attribute-",
) : GmPrincipalResolver {
    override fun resolve(request: GmHttpRequestContext): GmPrincipal? {
        val id = request.firstHeader(idHeader)?.takeIf { it.isNotBlank() } ?: return null
        return GmPrincipal(
            id = id,
            displayName = request.firstHeader(displayNameHeader)?.takeIf { it.isNotBlank() },
            attributes = request.headers
                .filterKeys { it.startsWith(attributeHeaderPrefix.lowercase()) }
                .mapKeys { (key, _) -> key.removePrefix(attributeHeaderPrefix.lowercase()) }
                .mapValues { (_, values) -> values.joinToString(",") },
        )
    }
}
