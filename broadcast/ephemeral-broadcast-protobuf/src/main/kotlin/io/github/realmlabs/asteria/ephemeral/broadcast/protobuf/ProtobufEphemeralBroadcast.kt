package io.github.realmlabs.asteria.ephemeral.broadcast.protobuf

import com.google.protobuf.GeneratedMessage
import io.github.realmlabs.asteria.ephemeral.broadcast.EphemeralBroadcastBus
import io.github.realmlabs.asteria.ephemeral.broadcast.EphemeralBroadcastSubscriber
import io.github.realmlabs.asteria.ephemeral.broadcast.EphemeralBroadcastSubscription
import io.github.realmlabs.asteria.ephemeral.broadcast.EphemeralBroadcastTopic
import io.github.realmlabs.asteria.protobuf.ProtobufMessageRegistry
import java.io.Serializable
import kotlin.reflect.KClass

/**
 * Serializable protobuf payload for ephemeral cluster broadcast.
 *
 * The [type] is the key from a [ProtobufMessageRegistry]. Ephemeral broadcast does not
 * reuse client protocol ids by default; applications can choose stable type
 * names such as `game.chat.WorldNotice` or numeric strings if that fits their
 * deployment.
 */
data class ProtobufEphemeralBroadcastPayload(
    val type: String,
    val payload: ByteArray,
) : Serializable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ProtobufEphemeralBroadcastPayload

        if (type != other.type) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Encodes a protobuf message for [EphemeralBroadcastBus] using a string-keyed registry.
 */
fun ProtobufMessageRegistry<String>.encodeBroadcast(message: GeneratedMessage): ProtobufEphemeralBroadcastPayload {
    val encoded = encode(message)
    return ProtobufEphemeralBroadcastPayload(encoded.key, encoded.payload)
}

/**
 * Decodes a protobuf ephemeral broadcast payload back to a generated message.
 */
fun ProtobufMessageRegistry<String>.decodeBroadcast(payload: ProtobufEphemeralBroadcastPayload): GeneratedMessage {
    return decode(payload.type, payload.payload).message
}

/**
 * Publishes a protobuf message as an ephemeral broadcast payload.
 */
fun EphemeralBroadcastBus.publishProto(
    topic: EphemeralBroadcastTopic,
    registry: ProtobufMessageRegistry<String>,
    message: GeneratedMessage,
    ttlMillis: Long? = null,
    traceId: String? = null,
) {
    publish(topic, registry.encodeBroadcast(message), ttlMillis = ttlMillis, traceId = traceId)
}

/**
 * Subscribes to protobuf ephemeral broadcasts of type [M] and ignores other payload types on the same topic.
 */
inline fun <reified M : GeneratedMessage> EphemeralBroadcastBus.subscribeProto(
    topic: EphemeralBroadcastTopic,
    registry: ProtobufMessageRegistry<String>,
    noinline handler: (M) -> Unit,
): EphemeralBroadcastSubscription {
    return subscribeProto(topic, registry, M::class, handler)
}

/**
 * Subscribes to protobuf ephemeral broadcasts for [messageClass] and ignores other payload types on the same topic.
 */
fun <M : GeneratedMessage> EphemeralBroadcastBus.subscribeProto(
    topic: EphemeralBroadcastTopic,
    registry: ProtobufMessageRegistry<String>,
    messageClass: KClass<M>,
    handler: (M) -> Unit,
): EphemeralBroadcastSubscription {
    return subscribe(topic, EphemeralBroadcastSubscriber { envelope ->
        val payload = envelope.payload as? ProtobufEphemeralBroadcastPayload ?: return@EphemeralBroadcastSubscriber
        val message = registry.decodeBroadcast(payload)
        if (!messageClass.java.isInstance(message)) return@EphemeralBroadcastSubscriber
        handler(messageClass.java.cast(message))
    })
}
