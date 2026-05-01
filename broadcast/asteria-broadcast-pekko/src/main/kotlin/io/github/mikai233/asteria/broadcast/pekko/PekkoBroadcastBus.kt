package io.github.mikai233.asteria.broadcast.pekko

import io.github.mikai233.asteria.broadcast.BroadcastBus
import io.github.mikai233.asteria.broadcast.BroadcastEnvelope
import io.github.mikai233.asteria.broadcast.BroadcastSubscriber
import io.github.mikai233.asteria.broadcast.BroadcastSubscription
import io.github.mikai233.asteria.broadcast.BroadcastTopic
import io.github.mikai233.asteria.broadcast.LocalBroadcastBus
import org.apache.pekko.actor.AbstractActor
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.Props
import org.apache.pekko.cluster.pubsub.DistributedPubSub
import org.apache.pekko.cluster.pubsub.DistributedPubSubMediator.Publish
import org.apache.pekko.cluster.pubsub.DistributedPubSubMediator.Subscribe
import org.apache.pekko.cluster.pubsub.DistributedPubSubMediator.SubscribeAck
import org.apache.pekko.cluster.pubsub.DistributedPubSubMediator.Unsubscribe
import org.apache.pekko.cluster.pubsub.DistributedPubSubMediator.UnsubscribeAck

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
    private val localBus: LocalBroadcastBus = LocalBroadcastBus(),
) : BroadcastBus, AutoCloseable {
    private val mediator: ActorRef = DistributedPubSub.get(system).mediator()
    private val bridge: ActorRef = system.actorOf(PekkoBroadcastBridge.props(localBus), "asteriaBroadcastBridge")

    override fun subscribe(topic: BroadcastTopic, subscriber: BroadcastSubscriber): BroadcastSubscription {
        val wasEmpty = localBus.subscriberCount(topic) == 0
        val localSubscription = localBus.subscribe(topic, subscriber)
        if (wasEmpty && localBus.subscriberCount(topic) == 1) {
            mediator.tell(Subscribe(topic.value, bridge), ActorRef.noSender())
        }
        return BroadcastSubscription {
            localSubscription.close()
            if (localBus.subscriberCount(topic) == 0) {
                mediator.tell(Unsubscribe(topic.value, bridge), ActorRef.noSender())
            }
        }
    }

    override fun unsubscribe(topic: BroadcastTopic, subscriber: BroadcastSubscriber) {
        localBus.unsubscribe(topic, subscriber)
        if (localBus.subscriberCount(topic) == 0) {
            mediator.tell(Unsubscribe(topic.value, bridge), ActorRef.noSender())
        }
    }

    override fun publish(envelope: BroadcastEnvelope) {
        if (envelope.isExpired()) return
        mediator.tell(Publish(envelope.topic.value, envelope), ActorRef.noSender())
    }

    override fun close() {
        localBus.topics().forEach { topic ->
            mediator.tell(Unsubscribe(topic.value, bridge), ActorRef.noSender())
        }
        system.stop(bridge)
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
