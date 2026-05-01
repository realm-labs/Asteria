package io.github.mikai233.asteria.gateway

import java.nio.ByteBuffer

/**
 * Minimal common packet shape for protocols that use an integer message id and raw payload.
 *
 * This is intentionally small. Applications that need sequence id, flags, compression markers, or auth metadata can use
 * their own packet type and [GatewayPacketCodec].
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
 * Default binary packet codec used by the Netty adapter.
 *
 * The header is:
 * - packet index: `Int`
 * - message id: `Int`
 * - payload length: `Int`
 * - payload bytes
 *
 * The packet index is a simple ordered-delivery guard for transports that should preserve order. It is not exposed as
 * business data. Applications that need request sequence ids should define their own packet type/codec.
 */
class IndexedBinaryGatewayPacketCodec(
    private val maxPacketIndex: Int = 65_536,
) : GatewayPacketCodec<BinaryGatewayPacket> {
    private var sendPacketIndex = 0
    private var receivePacketIndex = 0

    override fun decode(frame: GatewayFrame): BinaryGatewayPacket {
        val buffer = ByteBuffer.wrap(frame.bytes)
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

    override fun encode(packet: BinaryGatewayPacket): GatewayFrame {
        val buffer = ByteBuffer.allocate(Int.SIZE_BYTES * 3 + packet.payload.size)
        buffer.putInt(sendPacketIndex)
        buffer.putInt(packet.messageId)
        buffer.putInt(packet.payload.size)
        buffer.put(packet.payload)
        sendPacketIndex = (sendPacketIndex + 1) % maxPacketIndex
        return GatewayFrame(buffer.array())
    }
}
