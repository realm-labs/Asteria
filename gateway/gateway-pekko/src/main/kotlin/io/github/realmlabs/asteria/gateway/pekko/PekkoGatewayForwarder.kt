package io.github.realmlabs.asteria.gateway.pekko

import io.github.realmlabs.asteria.cluster.pekko.EntityShardRegistry
import io.github.realmlabs.asteria.cluster.pekko.SingletonActorRegistry
import io.github.realmlabs.asteria.gateway.GatewayForwarder
import io.github.realmlabs.asteria.gateway.GatewayRoute
import io.github.realmlabs.asteria.gateway.GatewaySessionContext
import io.github.realmlabs.asteria.message.RouteTarget
import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSelection
import org.apache.pekko.actor.ActorSystem
import org.slf4j.LoggerFactory

/**
 * Converts an inbound gateway packet into actor messages.
 *
 * The framework does not know a game's envelope shape. Some games send protobuf directly to a singleton, while shard
 * entities usually need an envelope carrying the entity id and the client session identity. Applications should provide
 * that conversion here.
 */
interface PekkoGatewayMessageFactory<P : Any> {
    fun entityMessage(context: GatewaySessionContext, route: GatewayRoute, packet: P): Any

    fun singletonMessage(context: GatewaySessionContext, route: GatewayRoute, packet: P): Any

    fun serviceMessage(context: GatewaySessionContext, route: GatewayRoute, packet: P): Any

    fun localMessage(context: GatewaySessionContext, route: GatewayRoute, packet: P): Any
}

/**
 * Default factory that forwards the packet itself.
 *
 * This is useful for local handlers and for applications whose packet type is already the backend actor message. Most
 * shard-based game servers should provide a game-specific envelope factory.
 */
class DirectPekkoGatewayMessageFactory<P : Any> : PekkoGatewayMessageFactory<P> {
    override fun entityMessage(context: GatewaySessionContext, route: GatewayRoute, packet: P): Any = packet

    override fun singletonMessage(context: GatewaySessionContext, route: GatewayRoute, packet: P): Any = packet

    override fun serviceMessage(context: GatewaySessionContext, route: GatewayRoute, packet: P): Any = packet

    override fun localMessage(context: GatewaySessionContext, route: GatewayRoute, packet: P): Any = packet
}

fun interface PekkoGatewayLocalHandler<P : Any> {
    fun handle(context: GatewaySessionContext, route: GatewayRoute, packet: P)
}

/**
 * Pekko implementation of [GatewayForwarder].
 *
 * Entity and singleton targets use the registries populated by `PekkoRuntimeModule`. Service targets use actor
 * selection, because a service path may be local, remote, or a cluster-aware actor selection chosen by the application.
 */
class PekkoGatewayForwarder<P : Any>(
    private val system: ActorSystem,
    private val shards: EntityShardRegistry,
    private val singletons: SingletonActorRegistry,
    private val messageFactory: PekkoGatewayMessageFactory<P> = DirectPekkoGatewayMessageFactory(),
    private val localHandler: PekkoGatewayLocalHandler<P>? = null,
    private val sender: ActorRef? = null,
    private val metrics: Metrics = NoopMetrics,
) : GatewayForwarder<P> {
    private val logger = LoggerFactory.getLogger(PekkoGatewayForwarder::class.java)

    override suspend fun forward(context: GatewaySessionContext, route: GatewayRoute, packet: P) {
        val tags = route.target.metricTags()
        val startedAt = System.nanoTime()
        metrics.counter("asteria.gateway.pekko.forward.total", tags).increment()
        try {
            when (val target = route.target) {
                is RouteTarget.Entity -> {
                    val ref = shards[target.kind]
                    ref.tell(messageFactory.entityMessage(context, route, packet), sender)
                }

                is RouteTarget.Singleton -> {
                    val ref = singletons[target.name]
                    ref.tell(messageFactory.singletonMessage(context, route, packet), sender)
                }

                is RouteTarget.Service -> {
                    val selection = serviceSelection(target)
                    selection.tell(messageFactory.serviceMessage(context, route, packet), sender)
                }

                RouteTarget.GatewayLocal -> {
                    val handler = requireNotNull(localHandler) {
                        "gateway local handler is required for ${RouteTarget.GatewayLocal}"
                    }
                    handler.handle(context, route, packet)
                }
            }
            metrics.counter("asteria.gateway.pekko.forward.succeeded.total", tags).increment()
        } catch (error: Throwable) {
            metrics.counter("asteria.gateway.pekko.forward.failed.total", tags).increment()
            logger.error("Pekko gateway forward failed target={}", tags.asMap()["target"], error)
            throw error
        } finally {
            metrics.timer("asteria.gateway.pekko.forward.duration", tags)
                .record((System.nanoTime() - startedAt) / 1_000_000)
        }
    }

    private fun serviceSelection(target: RouteTarget.Service): ActorSelection {
        return system.actorSelection(target.path)
    }
}

private fun RouteTarget.metricTags(): MetricTags {
    val target = when (this) {
        is RouteTarget.Entity -> "entity"
        RouteTarget.GatewayLocal -> "local"
        is RouteTarget.Service -> "service"
        is RouteTarget.Singleton -> "singleton"
    }
    return MetricTags.of("target" to target)
}
