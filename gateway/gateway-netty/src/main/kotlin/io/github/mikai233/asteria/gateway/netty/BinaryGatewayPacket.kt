package io.github.mikai233.asteria.gateway.netty

import java.nio.ByteBuffer

/**
 * Small optional packet shape for Netty pipelines that use an integer message id and raw payload.
 *
 * This is intentionally kept in the Netty adapter instead of gateway-core. Applications with their own packet headers
 * should provide their own Netty handlers and can skip this type completely.
 */
data class BinaryGatewayPacket(
    val messageId: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BinaryGatewayPacket

        if (messageId != other.messageId) return false
        return payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = messageId
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Optional Netty packet codec backing [PacketCodec].
 *
 * Header layout:
 * - packet index: `Int`
 * - message id: `Int`
 * - payload length: `Int`
 * - payload bytes
 */
class IndexedBinaryGatewayPacketCodec(
    private val maxPacketIndex: Int = 65_536,
) {
    private var sendPacketIndex = 0
    private var receivePacketIndex = 0

    fun decode(bytes: ByteArray): BinaryGatewayPacket {
        val buffer = ByteBuffer.wrap(bytes)
        require(buffer.remaining() >= Int.SIZE_BYTES * 3) { "gateway packet is too short" }
        val packetIndex = buffer.int
        require(packetIndex == receivePacketIndex) {
            "unexpected gateway packet index: expected=$receivePacketIndex actual=$packetIndex"
        }
        val messageId = buffer.int
        val payloadLength = buffer.int
        require(payloadLength >= 0 && payloadLength == buffer.remaining()) {
            "invalid gateway packet payload length $payloadLength"
        }
        val payload = ByteArray(payloadLength)
        buffer.get(payload)
        receivePacketIndex = (receivePacketIndex + 1) % maxPacketIndex
        return BinaryGatewayPacket(messageId, payload)
    }

    fun encode(packet: BinaryGatewayPacket): ByteArray {
        val buffer = ByteBuffer.allocate(Int.SIZE_BYTES * 3 + packet.payload.size)
        buffer.putInt(sendPacketIndex)
        buffer.putInt(packet.messageId)
        buffer.putInt(packet.payload.size)
        buffer.put(packet.payload)
        sendPacketIndex = (sendPacketIndex + 1) % maxPacketIndex
        return buffer.array()
    }
}
