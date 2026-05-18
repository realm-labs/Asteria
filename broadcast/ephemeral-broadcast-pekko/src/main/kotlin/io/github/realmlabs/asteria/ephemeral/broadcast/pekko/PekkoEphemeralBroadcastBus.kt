package io.github.realmlabs.asteria.ephemeral.broadcast.pekko

import io.github.realmlabs.asteria.ephemeral.broadcast.*
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import org.apache.pekko.actor.AbstractActor
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.Props
import org.apache.pekko.cluster.pubsub.DistributedPubSub
import org.apache.pekko.cluster.pubsub.DistributedPubSubMediator.*
import org.slf4j.LoggerFactory

/**
 * Cluster-wide ephemeral broadcast bus backed by Pekko Distributed PubSub.
 *
 * Each node keeps a local subscriber table. Publishing sends the envelope to the
 * cluster topic, and every node that has local subscribers for that topic
 * dispatches the received envelope locally. Delivery is at-most-once and follows
 * Pekko Distributed PubSub ordering guarantees.
 */
class PekkoEphemeralBroadcastBus(
    private val system: ActorSystem,
    localBusOverride: LocalEphemeralBroadcastBus? = null,
    private val metrics: Metrics = NoopMetrics,
) : EphemeralBroadcastBus, AutoCloseable {
    private val logger = LoggerFactory.getLogger(PekkoEphemeralBroadcastBus::class.java)
    private val localBus: LocalEphemeralBroadcastBus = localBusOverride ?: LocalEphemeralBroadcastBus(metrics)
    private val mediator: ActorRef = DistributedPubSub.get(system).mediator()
    private val bridge: ActorRef =
        system.actorOf(PekkoEphemeralBroadcastBridge.props(localBus), "asteriaEphemeralBroadcastBridge")

    override fun subscribe(
        topic: EphemeralBroadcastTopic,
        subscriber: EphemeralBroadcastSubscriber
    ): EphemeralBroadcastSubscription {
        val wasEmpty = localBus.subscriberCount(topic) == 0
        val localSubscription = localBus.subscribe(topic, subscriber)
        if (wasEmpty && localBus.subscriberCount(topic) == 1) {
            metrics.counter("asteria.ephemeral_broadcast.pekko.subscribe.topic.total").increment()
            mediator.tell(Subscribe(topic.value, bridge), ActorRef.noSender())
        }
        return EphemeralBroadcastSubscription {
            localSubscription.close()
            if (localBus.subscriberCount(topic) == 0) {
                metrics.counter("asteria.ephemeral_broadcast.pekko.unsubscribe.topic.total").increment()
                mediator.tell(Unsubscribe(topic.value, bridge), ActorRef.noSender())
            }
        }
    }

    override fun unsubscribe(topic: EphemeralBroadcastTopic, subscriber: EphemeralBroadcastSubscriber) {
        localBus.unsubscribe(topic, subscriber)
        if (localBus.subscriberCount(topic) == 0) {
            metrics.counter("asteria.ephemeral_broadcast.pekko.unsubscribe.topic.total").increment()
            mediator.tell(Unsubscribe(topic.value, bridge), ActorRef.noSender())
        }
    }

    override fun publish(envelope: EphemeralBroadcastEnvelope) {
        metrics.counter("asteria.ephemeral_broadcast.pekko.publish.total").increment()
        if (envelope.isExpired()) {
            metrics.counter("asteria.ephemeral_broadcast.pekko.publish.expired.total").increment()
            return
        }
        mediator.tell(Publish(envelope.topic.value, envelope), ActorRef.noSender())
    }

    override fun close() {
        localBus.topics().forEach { topic ->
            mediator.tell(Unsubscribe(topic.value, bridge), ActorRef.noSender())
        }
        system.stop(bridge)
        logger.info("Pekko ephemeral broadcast bus closed")
    }
}

private class PekkoEphemeralBroadcastBridge(
    private val localBus: LocalEphemeralBroadcastBus,
) : AbstractActor() {
    override fun createReceive(): Receive {
        return receiveBuilder()
            .match(EphemeralBroadcastEnvelope::class.java) { localBus.publish(it) }
            .match(SubscribeAck::class.java) { }
            .match(UnsubscribeAck::class.java) { }
            .build()
    }

    companion object {
        fun props(localBus: LocalEphemeralBroadcastBus): Props {
            return Props.create(PekkoEphemeralBroadcastBridge::class.java, localBus)
        }
    }
}
