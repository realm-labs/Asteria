package io.github.realmlabs.asteria.gm.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DefaultGmPolicyEvaluatorTest {
    private val evaluator = DefaultGmPolicyEvaluator()
    private val permission = GmPermissionKey("gm.test.read")

    @Test
    fun `allows permission without scope`() {
        val decision = evaluator.evaluate(
            GmAuthorizationRequest(
                principal = GmPrincipal("alice", permissions = setOf(permission)),
                permission = permission,
            ),
        )

        assertEquals(GmAuthorizationDecision.Allowed, decision)
    }

    @Test
    fun `denies scoped request when principal has no matching scope key`() {
        val decision = evaluator.evaluate(
            GmAuthorizationRequest(
                principal = GmPrincipal("alice", permissions = setOf(permission)),
                permission = permission,
                scope = GmResourceScope(mapOf("server" to "s1")),
            ),
        )

        assertIs<GmAuthorizationDecision.Denied>(decision)
    }

    @Test
    fun `allows scoped request when wildcard scope is present`() {
        val decision = evaluator.evaluate(
            GmAuthorizationRequest(
                principal = GmPrincipal(
                    id = "alice",
                    permissions = setOf(permission),
                    scopeValues = mapOf("server" to setOf("*")),
                ),
                permission = permission,
                scope = GmResourceScope(mapOf("server" to "s1")),
            ),
        )

        assertEquals(GmAuthorizationDecision.Allowed, decision)
    }
}
