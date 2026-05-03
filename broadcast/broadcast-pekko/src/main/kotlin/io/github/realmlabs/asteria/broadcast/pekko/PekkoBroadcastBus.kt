package io.github.realmlabs.asteria.broadcast.pekko

import io.github.realmlabs.asteria.broadcast.*
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
 * Cluster-wide broadcast bus backed by Pekko Distributed PubSub.
 *
 * Each node keeps a local subscriber table. Publishing sends the envelope to the
 * cluster topic, and every node that has local subscribers for that topic
 * dispatches the received envelope locally. Delivery is at-most-once and follows
 * Pekko Distributed PubSub ordering guarantees.
 */
class PekkoBroadcastBus(
    private val system: ActorSystem,
    localBusOverride: LocalBroadcastBus? = null,
    private val metrics: Metrics = NoopMetrics,
) : BroadcastBus, AutoCloseable {
    private val logger = LoggerFactory.getLogger(PekkoBroadcastBus::class.java)
    private val localBus: LocalBroadcastBus = localBusOverride ?: LocalBroadcastBus(metrics)
    private val mediator: ActorRef = DistributedPubSub.get(system).mediator()
    private val bridge: ActorRef = system.actorOf(PekkoBroadcastBridge.props(localBus), "asteriaBroadcastBridge")

    override fun subscribe(topic: BroadcastTopic, subscriber: BroadcastSubscriber): BroadcastSubscription {
        val wasEmpty = localBus.subscriberCount(topic) == 0
        val localSubscription = localBus.subscribe(topic, subscriber)
        if (wasEmpty && localBus.subscriberCount(topic) == 1) {
            metrics.counter("asteria.broadcast.pekko.subscribe.topic.total").increment()
            mediator.tell(Subscribe(topic.value, bridge), ActorRef.noSender())
        }
        return BroadcastSubscription {
            localSubscription.close()
            if (localBus.subscriberCount(topic) == 0) {
                metrics.counter("asteria.broadcast.pekko.unsubscribe.topic.total").increment()
                mediator.tell(Unsubscribe(topic.value, bridge), ActorRef.noSender())
            }
        }
    }

    override fun unsubscribe(topic: BroadcastTopic, subscriber: BroadcastSubscriber) {
        localBus.unsubscribe(topic, subscriber)
        if (localBus.subscriberCount(topic) == 0) {
            metrics.counter("asteria.broadcast.pekko.unsubscribe.topic.total").increment()
            mediator.tell(Unsubscribe(topic.value, bridge), ActorRef.noSender())
        }
    }

    override fun publish(envelope: BroadcastEnvelope) {
        metrics.counter("asteria.broadcast.pekko.publish.total").increment()
        if (envelope.isExpired()) {
            metrics.counter("asteria.broadcast.pekko.publish.expired.total").increment()
            return
        }
        mediator.tell(Publish(envelope.topic.value, envelope), ActorRef.noSender())
    }

    override fun close() {
        localBus.topics().forEach { topic ->
            mediator.tell(Unsubscribe(topic.value, bridge), ActorRef.noSender())
        }
        system.stop(bridge)
        logger.info("Pekko broadcast bus closed")
    }
}

private class PekkoBroadcastBridge(
    private val localBus: LocalBroadcastBus,
) : AbstractActor() {
    override fun createReceive(): Receive {
        return receiveBuilder()
            .match(BroadcastEnvelope::class.java) { localBus.publish(it) }
            .match(SubscribeAck::class.java) { }
            .match(UnsubscribeAck::class.java) { }
            .build()
    }

    companion object {
        fun props(localBus: LocalBroadcastBus): Props {
            return Props.create(PekkoBroadcastBridge::class.java, localBus)
        }
    }
}
