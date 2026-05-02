package io.github.mikai233.asteria.broadcast

import io.github.mikai233.asteria.observability.Metrics
import io.github.mikai233.asteria.observability.NoopMetrics
import org.slf4j.LoggerFactory

/**
 * In-memory [BroadcastBus] implementation for one JVM.
 *
 * This implementation is useful for local applications, tests, and as the local
 * subscriber table underneath cluster implementations.
 */
open class LocalBroadcastBus(
    private val metrics: Metrics = NoopMetrics,
) : BroadcastBus {
    private val logger = LoggerFactory.getLogger(LocalBroadcastBus::class.java)
    private val subscribersByTopic: MutableMap<BroadcastTopic, LinkedHashSet<BroadcastSubscriber>> = linkedMapOf()

    override fun subscribe(topic: BroadcastTopic, subscriber: BroadcastSubscriber): BroadcastSubscription {
        val added = subscribersByTopic.getOrPut(topic) { linkedSetOf() }.add(subscriber)
        if (added) {
            metrics.counter("asteria.broadcast.local.subscribe.total").increment()
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
        metrics.counter("asteria.broadcast.local.unsubscribe.total").increment()
        afterUnsubscribe(topic, remaining)
    }

    override fun publish(envelope: BroadcastEnvelope) {
        metrics.counter("asteria.broadcast.local.publish.total").increment()
        if (envelope.isExpired()) {
            metrics.counter("asteria.broadcast.local.publish.expired.total").increment()
            return
        }
        val subscribers = subscribersByTopic[envelope.topic].orEmpty().toList()
        if (subscribers.isEmpty()) {
            metrics.counter("asteria.broadcast.local.publish.no_subscriber.total").increment()
            return
        }
        val startedAt = System.nanoTime()
        subscribers.forEach { subscriber ->
            try {
                subscriber.onBroadcast(envelope)
                metrics.counter("asteria.broadcast.local.delivery.total").increment()
            } catch (error: Throwable) {
                metrics.counter("asteria.broadcast.local.delivery.failed.total").increment()
                logger.error("local broadcast delivery failed", error)
                throw error
            }
        }
        metrics.timer("asteria.broadcast.local.delivery.duration")
            .record((System.nanoTime() - startedAt) / 1_000_000)
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
