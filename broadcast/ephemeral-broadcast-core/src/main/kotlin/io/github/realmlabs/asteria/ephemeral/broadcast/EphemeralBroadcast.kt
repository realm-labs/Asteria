package io.github.realmlabs.asteria.ephemeral.broadcast

import java.io.Serializable

/**
 * Logical ephemeral broadcast topic.
 *
 * Topics are application-defined routing keys such as `global`, `world:1001`, or
 * `guild:42`. The framework treats them as opaque strings and does not apply
 * business filtering after a message reaches a topic.
 */
@JvmInline
value class EphemeralBroadcastTopic(val value: String) : Serializable {
    init {
        require(value.isNotBlank()) { "ephemeral broadcast topic must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Message delivered through an [EphemeralBroadcastBus].
 *
 * The payload type is intentionally open because the core ephemeral broadcast API is not
 * tied to protobuf, gateway sessions, or a specific actor runtime. Cluster
 * implementations may still require payloads to be serializable by their
 * transport. For Pekko cluster broadcast, the payload must be supported by the
 * ActorSystem serializer configuration.
 */
data class EphemeralBroadcastEnvelope(
    val topic: EphemeralBroadcastTopic,
    val payload: Any,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val ttlMillis: Long? = null,
    val traceId: String? = null,
) : Serializable {
    init {
        require(ttlMillis == null || ttlMillis > 0) { "ephemeral broadcast ttlMillis must be positive" }
    }

    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val ttl = ttlMillis ?: return false
        return nowMillis - createdAtMillis >= ttl
    }
}

/**
 * Receives ephemeral envelopes that match subscribed topics.
 *
 * Implementations should avoid blocking. A slow subscriber delays local delivery
 * to later subscribers on the same publishing call. Subscriber failures are
 * handled by the bus implementation; [LocalEphemeralBroadcastBus] logs them and continues
 * delivering to later local subscribers.
 */
fun interface EphemeralBroadcastSubscriber {
    fun onBroadcast(envelope: EphemeralBroadcastEnvelope)
}

/**
 * Handle returned by [EphemeralBroadcastBus.subscribe].
 */
fun interface EphemeralBroadcastSubscription : AutoCloseable {
    override fun close()
}

/**
 * Topic based ephemeral broadcast service.
 *
 * This API is for online notifications and cache/state invalidation signals. The default contract is at-most-once
 * delivery with no persistence, replay, or offline compensation. Use a durable event stream for critical business facts.
 */
interface EphemeralBroadcastBus {
    fun subscribe(
        topic: EphemeralBroadcastTopic,
        subscriber: EphemeralBroadcastSubscriber
    ): EphemeralBroadcastSubscription

    fun unsubscribe(topic: EphemeralBroadcastTopic, subscriber: EphemeralBroadcastSubscriber)

    fun publish(envelope: EphemeralBroadcastEnvelope)

    fun publish(
        topic: EphemeralBroadcastTopic,
        payload: Any,
        ttlMillis: Long? = null,
        traceId: String? = null,
    ) {
        publish(EphemeralBroadcastEnvelope(topic = topic, payload = payload, ttlMillis = ttlMillis, traceId = traceId))
    }
}
