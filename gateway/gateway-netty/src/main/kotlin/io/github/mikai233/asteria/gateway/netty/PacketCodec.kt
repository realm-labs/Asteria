package io.github.mikai233.asteria.gateway.netty

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageCodec

/**
 * Stateful Netty packet codec for the default integer-message-id binary packet.
 *
 * TCP/WebSocket/KCP transports can still use different Netty handlers for their frame boundaries. This codec only owns
 * the application packet header used by the default protobuf adapter: packet index, message id, payload length, payload.
 */
class PacketCodec : MessageToMessageCodec<ByteBuf, BinaryGatewayPacket>() {
    private val packets = IndexedBinaryGatewayPacketCodec()

    override fun encode(ctx: ChannelHandlerContext, msg: BinaryGatewayPacket, out: MutableList<Any>) {
        out.add(Unpooled.wrappedBuffer(packets.encode(msg)))
    }

    override fun decode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        val bytes = ByteArray(msg.readableBytes())
        msg.readBytes(bytes)
        val packet = runCatching { packets.decode(bytes) }.getOrElse {
            ctx.close()
            return
        }
        out.add(packet)
    }
}
