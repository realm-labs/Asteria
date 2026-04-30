package io.github.mikai233.asteria.gateway.netty

import io.github.mikai233.asteria.protocol.protobuf.ProtoFrame
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageCodec

class PacketCodec : MessageToMessageCodec<ByteBuf, ProtoFrame>() {
    private var sendPacketIndex = 0
    private var receivePacketIndex = 0

    override fun encode(ctx: ChannelHandlerContext, msg: ProtoFrame, out: MutableList<Any>) {
        val payload = msg.payload
        val buffer = ctx.alloc().buffer(Int.SIZE_BYTES * 3 + payload.size)
        buffer.writeInt(sendPacketIndex)
        buffer.writeInt(msg.id)
        buffer.writeInt(payload.size)
        buffer.writeBytes(payload)
        out.add(buffer)
        sendPacketIndex = (sendPacketIndex + 1) % MAX_PACKET_INDEX
    }

    override fun decode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        val packetIndex = msg.readInt()
        if (packetIndex != receivePacketIndex) {
            ctx.close()
            return
        }
        val id = msg.readInt()
        val length = msg.readInt()
        val payload = ByteArray(length)
        msg.readBytes(payload)
        out.add(ProtoFrame(id, payload))
        receivePacketIndex = (receivePacketIndex + 1) % MAX_PACKET_INDEX
    }

    private companion object {
        const val MAX_PACKET_INDEX = 65_536
    }
}
