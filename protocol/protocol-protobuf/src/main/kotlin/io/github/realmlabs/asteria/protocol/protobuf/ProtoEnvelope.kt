package io.github.realmlabs.asteria.protocol.protobuf

import com.google.protobuf.GeneratedMessage

/**
 * Decoded protobuf packet received from a client.
 *
 * [id] is the protocol id from the wire frame after direction validation. [message] is the parsed protobuf object used
 * by gateway routing.
 */
data class ClientProtoEnvelope(
    val id: Int,
    val message: GeneratedMessage,
)

/**
 * Decoded protobuf packet intended for a client.
 */
data class ServerProtoEnvelope(
    val message: GeneratedMessage,
)

/**
 * Wire-level protobuf frame before transport-specific framing is applied.
 *
 * [payload] is the serialized protobuf message body. The type overrides array equality so frames can be compared in
 * tests and caches without relying on reference equality.
 */
data class ProtoFrame(
    val id: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ProtoFrame

        if (id != other.id) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
