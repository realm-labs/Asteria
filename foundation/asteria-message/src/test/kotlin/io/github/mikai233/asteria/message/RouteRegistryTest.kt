package io.github.mikai233.asteria.message

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RouteRegistryTest {
    @Test
    fun `route registry rejects duplicate message type`() {
        assertFailsWith<IllegalStateException> {
            RouteRegistryBuilder().apply {
                route<String>(RouteTarget.GatewayLocal)
                route<String>(RouteTarget.GatewayLocal)
            }.build()
        }
    }

    @Test
    fun `protocol route resolves id with type check`() {
        val route = ProtocolRoute(String::class, RouteTarget.GatewayLocal) { it.length }

        assertEquals(5, route.resolveId("hello"))
        assertFailsWith<IllegalArgumentException> {
            route.resolveId(1)
        }
    }
}
