package io.github.realmlabs.asteria.gm.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GmAuthorizationPolicyTest {
    private val request = GmAuthorizationRequest(
        principal = GmPrincipal("alice"),
        operation = GmOperation(
            action = GmAction("gm.test.read"),
            resource = GmResource("test.resource", "resource-1"),
        ),
    )

    @Test
    fun `deny all policy rejects operations`() = runBlocking {
        val decision = DenyAllGmAuthorizationPolicy.authorize(request)

        assertIs<GmAuthorizationDecision.Denied>(decision)
    }

    @Test
    fun `allow all policy allows operations`() = runBlocking {
        val decision = AllowAllGmAuthorizationPolicy.authorize(request)

        assertEquals(GmAuthorizationDecision.Allowed, decision)
    }
}
