package io.github.realmlabs.asteria.ephemeral.broadcast

import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import org.slf4j.LoggerFactory
import java.util.concurrent.CancellationException

/**
 * In-memory [EphemeralBroadcastBus] implementation for one JVM.
 *
 * This implementation is useful for local applications, tests, and as the local
 * subscriber table underneath cluster implementations.
 */
open class LocalEphemeralBroadcastBus(
    private val metrics: Metrics = NoopMetrics,
) : EphemeralBroadcastBus {
    private val logger = LoggerFactory.getLogger(LocalEphemeralBroadcastBus::class.java)
    private val subscribersByTopic: MutableMap<EphemeralBroadcastTopic, LinkedHashSet<EphemeralBroadcastSubscriber>> =
        linkedMapOf()

    override fun subscribe(
        topic: EphemeralBroadcastTopic,
        subscriber: EphemeralBroadcastSubscriber
    ): EphemeralBroadcastSubscription {
        val added = subscribersByTopic.getOrPut(topic) { linkedSetOf() }.add(subscriber)
        if (added) {
            metrics.counter("asteria.ephemeral_broadcast.local.subscribe.total").increment()
            afterSubscribe(topic, subscriberCount(topic))
        }
        return EphemeralBroadcastSubscription { unsubscribe(topic, subscriber) }
    }

    override fun unsubscribe(topic: EphemeralBroadcastTopic, subscriber: EphemeralBroadcastSubscriber) {
        val subscribers = subscribersByTopic[topic] ?: return
        if (!subscribers.remove(subscriber)) return
        val remaining = subscribers.size
        if (subscribers.isEmpty()) {
            subscribersByTopic.remove(topic)
        }
        metrics.counter("asteria.ephemeral_broadcast.local.unsubscribe.total").increment()
        afterUnsubscribe(topic, remaining)
    }

    override fun publish(envelope: EphemeralBroadcastEnvelope) {
        metrics.counter("asteria.ephemeral_broadcast.local.publish.total").increment()
        if (envelope.isExpired()) {
            metrics.counter("asteria.ephemeral_broadcast.local.publish.expired.total").increment()
            return
        }
        val subscribers = subscribersByTopic[envelope.topic].orEmpty().toList()
        if (subscribers.isEmpty()) {
            metrics.counter("asteria.ephemeral_broadcast.local.publish.no_subscriber.total").increment()
            return
        }
        val startedAt = System.nanoTime()
        subscribers.forEach { subscriber ->
            try {
                subscriber.onBroadcast(envelope)
                metrics.counter("asteria.ephemeral_broadcast.local.delivery.total").increment()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                metrics.counter("asteria.ephemeral_broadcast.local.delivery.failed.total").increment()
                logger.error("local ephemeral broadcast delivery failed", error)
            }
        }
        metrics.timer("asteria.ephemeral_broadcast.local.delivery.duration")
            .record((System.nanoTime() - startedAt) / 1_000_000)
    }

    fun subscriberCount(topic: EphemeralBroadcastTopic): Int {
        return subscribersByTopic[topic]?.size ?: 0
    }

    fun topics(): Set<EphemeralBroadcastTopic> {
        return subscribersByTopic.keys.toSet()
    }

    protected open fun afterSubscribe(topic: EphemeralBroadcastTopic, subscriberCount: Int) = Unit

    protected open fun afterUnsubscribe(topic: EphemeralBroadcastTopic, subscriberCount: Int) = Unit
}
