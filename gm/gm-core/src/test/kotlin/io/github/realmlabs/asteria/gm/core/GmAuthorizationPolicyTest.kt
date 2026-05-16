package io.github.realmlabs.asteria.gm.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `deny all policy rejects operations`(): Unit = runBlocking {
        val decision = DenyAllGmAuthorizationPolicy.authorize(request)

        assertIs<GmAuthorizationDecision.Denied>(decision)
    }

    @Test
    fun `allow all policy allows operations`(): Unit = runBlocking {
        val decision = AllowAllGmAuthorizationPolicy.authorize(request)

        assertEquals(GmAuthorizationDecision.Allowed, decision)
    }

    @Test
    fun `composite audit sink records later sinks before rethrowing`(): Unit = runBlocking {
        val events = mutableListOf<String>()
        val sink = CompositeGmAuditSink(
            listOf(
                GmAuditSink {
                    events += "first"
                    error("audit failed")
                },
                GmAuditSink {
                    events += "second"
                },
            ),
        )

        val error = assertFailsWith<IllegalStateException> {
            sink.record(
                GmAuditEvent(
                    operatorId = "alice",
                    operation = request.operation,
                    success = true,
                ),
            )
        }

        assertEquals("audit failed", error.message)
        assertEquals(listOf("first", "second"), events)
    }
}
