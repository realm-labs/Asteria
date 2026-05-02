package io.github.mikai233.asteria.gateway

import io.github.mikai233.asteria.message.RouteTarget

/**
 * Resolved forwarding decision for one inbound gateway packet.
 */
data class GatewayRoute(
    val target: RouteTarget,
    val entityId: Any? = null,
)

fun interface GatewayRouteResolver<P : Any> {
    suspend fun resolve(context: GatewaySessionContext, packet: P): GatewayRoute
}

/**
 * Sends a routed packet to the chosen backend destination.
 *
 * A Pekko implementation can map [RouteTarget.Entity] to sharding, [RouteTarget.Singleton] to cluster singleton, and
 * [RouteTarget.Service] to a role/path selection. Applications can also provide a local-only implementation for tests.
 */
fun interface GatewayForwarder<P : Any> {
    suspend fun forward(context: GatewaySessionContext, route: GatewayRoute, packet: P)
}

class GatewayMessageDispatcher<P : Any>(
    private val routeResolver: GatewayRouteResolver<P>,
    private val forwarder: GatewayForwarder<P>,
) {
    suspend fun dispatch(context: GatewaySessionContext, packet: P): GatewayRoute {
        val route = routeResolver.resolve(context, packet)
        forwarder.forward(context, route, packet)
        return route
    }
}

/**
 * Convenience adapter for a complete inbound frame -> packet -> route -> forward pipeline.
 */
class GatewayInboundPipeline<P : Any>(
    private val packets: GatewayPacketPipeline<P>,
    private val dispatcher: GatewayMessageDispatcher<P>,
) {
    suspend fun receive(context: GatewaySessionContext, frame: GatewayFrame): GatewayRoute {
        val packet = packets.decode(context, frame)
        return dispatcher.dispatch(context, packet)
    }
}
