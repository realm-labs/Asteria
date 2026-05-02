package io.github.mikai233.asteria.protocol.protobuf

import com.google.protobuf.GeneratedMessage

data class ClientProtoEnvelope(
    val id: Int,
    val message: GeneratedMessage,
)

data class ServerProtoEnvelope(
    val message: GeneratedMessage,
)

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
