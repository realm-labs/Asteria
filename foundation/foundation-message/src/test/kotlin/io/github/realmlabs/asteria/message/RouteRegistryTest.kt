package io.github.realmlabs.asteria.message

import io.github.realmlabs.asteria.core.EntityKind
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

    @Test
    fun `dynamic route registry replaces and removes routes`() {
        val routes = DynamicRouteRegistry(
            listOf(
                ProtocolRoute(String::class, RouteTarget.GatewayLocal),
            ),
        )

        val previous = routes.replace<String>(RouteTarget.Entity(EntityKind("player"))) { it }

        assertEquals(RouteTarget.GatewayLocal, previous?.target)
        assertEquals(RouteTarget.Entity(EntityKind("player")), routes.routeFor(String::class)?.target)
        assertEquals("p1", routes.routeFor(String::class)?.resolveId("p1"))

        val removed = routes.remove<String>()

        assertEquals(RouteTarget.Entity(EntityKind("player")), removed?.target)
        assertEquals(null, routes.routeFor(String::class))
    }

    @Test
    fun `dynamic route registry snapshot is immutable`() {
        val routes = DynamicRouteRegistry(
            listOf(
                ProtocolRoute(String::class, RouteTarget.GatewayLocal),
            ),
        )
        val snapshot = routes.snapshot()

        routes.replace<String>(RouteTarget.Entity(EntityKind("player")))

        assertEquals(RouteTarget.GatewayLocal, snapshot.routeFor(String::class)?.target)
        assertEquals(RouteTarget.Entity(EntityKind("player")), routes.routeFor(String::class)?.target)
    }
}
