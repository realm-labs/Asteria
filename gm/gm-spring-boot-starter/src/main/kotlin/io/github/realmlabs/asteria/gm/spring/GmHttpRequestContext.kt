package io.github.realmlabs.asteria.gm.spring

import jakarta.servlet.http.HttpServletRequest
import java.util.*

/**
 * Servlet-independent request data used by GM authentication adapters.
 *
 * Keeping resolvers on this small value object makes it possible to plug in Spring Security, trusted gateway headers,
 * session state, or token verification without binding the core GM contracts to Servlet APIs.
 */
data class GmHttpRequestContext(
    val headers: Map<String, List<String>>,
    val remoteAddress: String?,
    val requestId: String?,
) {
    fun firstHeader(name: String): String? {
        return headers[name.lowercase()]?.firstOrNull()
    }

    companion object {
        fun from(request: HttpServletRequest): GmHttpRequestContext {
            return GmHttpRequestContext(
                headers = request.headerNames.asSequence()
                    .associate { name ->
                        name.lowercase() to request.getHeaders(name).asSequence().toList()
                    },
                remoteAddress = request.remoteAddr,
                requestId = request.getHeader("X-Request-Id") ?: request.getHeader("X-Correlation-Id"),
            )
        }
    }
}

private fun <T : Any> Enumeration<T>.asSequence(): Sequence<T> {
    return sequence {
        while (hasMoreElements()) {
            yield(nextElement())
        }
    }
}
