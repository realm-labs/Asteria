package io.github.realmlabs.asteria.gm.spring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeaderGmPrincipalResolverTest {
    private val resolver = HeaderGmPrincipalResolver()

    @Test
    fun `returns null when identity header is missing`() {
        val principal = resolver.resolve(GmHttpRequestContext(emptyMap(), null, null))

        assertNull(principal)
    }

    @Test
    fun `resolves principal attributes from trusted headers`() {
        val principal = resolver.resolve(
            GmHttpRequestContext(
                headers = mapOf(
                    "x-gm-user" to listOf("alice"),
                    "x-gm-display-name" to listOf("Alice"),
                    "x-gm-attribute-department" to listOf("ops"),
                    "x-gm-attribute-region" to listOf("cn"),
                ),
                remoteAddress = "127.0.0.1",
                requestId = "req-1",
            ),
        )

        requireNotNull(principal)
        assertEquals("alice", principal.id)
        assertEquals("Alice", principal.displayName)
        assertEquals("ops", principal.attributes["department"])
        assertEquals("cn", principal.attributes["region"])
    }
}
