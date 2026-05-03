package io.github.realmlabs.asteria.gateway

import io.github.realmlabs.asteria.message.RouteTarget
import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics

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
    private val metrics: Metrics = NoopMetrics,
) {
    suspend fun dispatch(context: GatewaySessionContext, packet: P): GatewayRoute {
        val tags = context.metricTags()
        val startedAt = System.nanoTime()
        metrics.counter("asteria.gateway.dispatch.total", tags).increment()
        val route = routeResolver.resolve(context, packet)
        try {
            forwarder.forward(context, route, packet)
            metrics.counter("asteria.gateway.dispatch.forwarded.total", tags + route.metricTags()).increment()
            return route
        } catch (error: Throwable) {
            metrics.counter("asteria.gateway.dispatch.failed.total", tags + route.metricTags()).increment()
            throw error
        } finally {
            metrics.timer("asteria.gateway.dispatch.duration", tags)
                .record((System.nanoTime() - startedAt) / 1_000_000)
        }
    }
}

private fun GatewaySessionContext.metricTags(): MetricTags {
    return MetricTags.of("transport" to session.transport.name)
}

private fun GatewayRoute.metricTags(): MetricTags {
    val targetType = when (target) {
        is RouteTarget.Entity -> "entity"
        is RouteTarget.Singleton -> "singleton"
        is RouteTarget.Service -> "service"
        RouteTarget.GatewayLocal -> "gateway_local"
    }
    return MetricTags.of("target" to targetType)
}
