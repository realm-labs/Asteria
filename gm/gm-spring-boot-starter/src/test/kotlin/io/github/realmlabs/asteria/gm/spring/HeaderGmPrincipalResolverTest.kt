package io.github.realmlabs.asteria.gm.spring

import io.github.realmlabs.asteria.gm.core.GmPermissionKey
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
    fun `resolves principal permissions roles and scopes from trusted headers`() {
        val principal = resolver.resolve(
            GmHttpRequestContext(
                headers = mapOf(
                    "x-gm-user" to listOf("alice"),
                    "x-gm-display-name" to listOf("Alice"),
                    "x-gm-roles" to listOf("admin, operator"),
                    "x-gm-permissions" to listOf("gm.cluster.read, gm.script.execute"),
                    "x-gm-scope-server" to listOf("s1, s2"),
                    "x-gm-scope-zone" to listOf("*"),
                ),
                remoteAddress = "127.0.0.1",
                requestId = "req-1",
            ),
        )

        requireNotNull(principal)
        assertEquals("alice", principal.id)
        assertEquals("Alice", principal.displayName)
        assertEquals(setOf("admin", "operator"), principal.roles)
        assertEquals(
            setOf(GmPermissionKey("gm.cluster.read"), GmPermissionKey("gm.script.execute")),
            principal.permissions,
        )
        assertEquals(setOf("s1", "s2"), principal.scopeValues["server"])
        assertEquals(setOf("*"), principal.scopeValues["zone"])
    }
}
