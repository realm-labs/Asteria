package io.github.mikai233.asteria.broadcast.protobuf

import com.google.protobuf.GeneratedMessage
import io.github.mikai233.asteria.broadcast.BroadcastBus
import io.github.mikai233.asteria.broadcast.BroadcastSubscriber
import io.github.mikai233.asteria.broadcast.BroadcastSubscription
import io.github.mikai233.asteria.broadcast.BroadcastTopic
import io.github.mikai233.asteria.protobuf.ProtobufMessageRegistry
import java.io.Serializable
import kotlin.reflect.KClass

/**
 * Serializable protobuf payload for cluster broadcast.
 *
 * The [type] is the key from a [ProtobufMessageRegistry]. Broadcast does not
 * reuse client protocol ids by default; applications can choose stable type
 * names such as `game.chat.WorldNotice` or numeric strings if that fits their
 * deployment.
 */
data class ProtobufBroadcastPayload(
    val type: String,
    val payload: ByteArray,
) : Serializable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ProtobufBroadcastPayload

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

fun ProtobufMessageRegistry<String>.encodeBroadcast(message: GeneratedMessage): ProtobufBroadcastPayload {
    val encoded = encode(message)
    return ProtobufBroadcastPayload(encoded.key, encoded.payload)
}

fun ProtobufMessageRegistry<String>.decodeBroadcast(payload: ProtobufBroadcastPayload): GeneratedMessage {
    return decode(payload.type, payload.payload).message
}

fun BroadcastBus.publishProto(
    topic: BroadcastTopic,
    registry: ProtobufMessageRegistry<String>,
    message: GeneratedMessage,
    ttlMillis: Long? = null,
    traceId: String? = null,
) {
    publish(topic, registry.encodeBroadcast(message), ttlMillis = ttlMillis, traceId = traceId)
}

inline fun <reified M : GeneratedMessage> BroadcastBus.subscribeProto(
    topic: BroadcastTopic,
    registry: ProtobufMessageRegistry<String>,
    noinline handler: (M) -> Unit,
): BroadcastSubscription {
    return subscribeProto(topic, registry, M::class, handler)
}

fun <M : GeneratedMessage> BroadcastBus.subscribeProto(
    topic: BroadcastTopic,
    registry: ProtobufMessageRegistry<String>,
    messageClass: KClass<M>,
    handler: (M) -> Unit,
): BroadcastSubscription {
    return subscribe(topic, BroadcastSubscriber { envelope ->
        val payload = envelope.payload as? ProtobufBroadcastPayload ?: return@BroadcastSubscriber
        val message = registry.decodeBroadcast(payload)
        if (!messageClass.java.isInstance(message)) return@BroadcastSubscriber
        handler(messageClass.java.cast(message))
    })
}
