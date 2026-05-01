package io.github.mikai233.asteria.broadcast

import java.io.Serializable

/**
 * Logical broadcast topic.
 *
 * Topics are application-defined routing keys such as `global`, `world:1001`, or
 * `guild:42`. The framework treats them as opaque strings and does not apply
 * business filtering after a message reaches a topic.
 */
@JvmInline
value class BroadcastTopic(val value: String) : Serializable {
    init {
        require(value.isNotBlank()) { "broadcast topic must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Message delivered through a [BroadcastBus].
 *
 * The payload type is intentionally open because the core broadcast API is not
 * tied to protobuf, gateway sessions, or a specific actor runtime. Cluster
 * implementations may still require payloads to be serializable by their
 * transport. For Pekko cluster broadcast, the payload must be supported by the
 * ActorSystem serializer configuration.
 */
data class BroadcastEnvelope(
    val topic: BroadcastTopic,
    val payload: Any,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val ttlMillis: Long? = null,
    val traceId: String? = null,
) : Serializable {
    init {
        require(ttlMillis == null || ttlMillis > 0) { "broadcast ttlMillis must be positive" }
    }

    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val ttl = ttlMillis ?: return false
        return nowMillis - createdAtMillis >= ttl
    }
}

/**
 * Receives envelopes that match subscribed topics.
 *
 * Implementations should avoid blocking. A slow subscriber delays local delivery
 * to later subscribers on the same publishing call.
 */
fun interface BroadcastSubscriber {
    fun onBroadcast(envelope: BroadcastEnvelope)
}

/**
 * Handle returned by [BroadcastBus.subscribe].
 */
fun interface BroadcastSubscription : AutoCloseable {
    override fun close()
}

/**
 * Topic based broadcast service.
 *
 * The default contract is at-most-once delivery. Implementations should preserve
 * local publish order for the same topic, but cluster-wide ordering is runtime
 * dependent and should not be used for critical state transitions.
 */
interface BroadcastBus {
    fun subscribe(topic: BroadcastTopic, subscriber: BroadcastSubscriber): BroadcastSubscription

    fun unsubscribe(topic: BroadcastTopic, subscriber: BroadcastSubscriber)

    fun publish(envelope: BroadcastEnvelope)

    fun publish(
        topic: BroadcastTopic,
        payload: Any,
        ttlMillis: Long? = null,
        traceId: String? = null,
    ) {
        publish(BroadcastEnvelope(topic = topic, payload = payload, ttlMillis = ttlMillis, traceId = traceId))
    }
}
