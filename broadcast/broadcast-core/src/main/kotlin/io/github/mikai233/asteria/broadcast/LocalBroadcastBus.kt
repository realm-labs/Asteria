package io.github.mikai233.asteria.broadcast

/**
 * In-memory [BroadcastBus] implementation for one JVM.
 *
 * This implementation is useful for local applications, tests, and as the local
 * subscriber table underneath cluster implementations.
 */
open class LocalBroadcastBus : BroadcastBus {
    private val subscribersByTopic: MutableMap<BroadcastTopic, LinkedHashSet<BroadcastSubscriber>> = linkedMapOf()

    override fun subscribe(topic: BroadcastTopic, subscriber: BroadcastSubscriber): BroadcastSubscription {
        val added = subscribersByTopic.getOrPut(topic) { linkedSetOf() }.add(subscriber)
        if (added) {
            afterSubscribe(topic, subscriberCount(topic))
        }
        return BroadcastSubscription { unsubscribe(topic, subscriber) }
    }

    override fun unsubscribe(topic: BroadcastTopic, subscriber: BroadcastSubscriber) {
        val subscribers = subscribersByTopic[topic] ?: return
        if (!subscribers.remove(subscriber)) return
        val remaining = subscribers.size
        if (subscribers.isEmpty()) {
            subscribersByTopic.remove(topic)
        }
        afterUnsubscribe(topic, remaining)
    }

    override fun publish(envelope: BroadcastEnvelope) {
        if (envelope.isExpired()) return
        subscribersByTopic[envelope.topic].orEmpty().toList().forEach { subscriber ->
            subscriber.onBroadcast(envelope)
        }
    }

    fun subscriberCount(topic: BroadcastTopic): Int {
        return subscribersByTopic[topic]?.size ?: 0
    }

    fun topics(): Set<BroadcastTopic> {
        return subscribersByTopic.keys.toSet()
    }

    protected open fun afterSubscribe(topic: BroadcastTopic, subscriberCount: Int) = Unit

    protected open fun afterUnsubscribe(topic: BroadcastTopic, subscriberCount: Int) = Unit
}
