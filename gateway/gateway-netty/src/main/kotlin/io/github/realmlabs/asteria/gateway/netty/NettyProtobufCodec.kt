package io.github.realmlabs.asteria.gateway.netty

import com.google.protobuf.GeneratedMessage
import io.github.realmlabs.asteria.protocol.protobuf.ClientProtoEnvelope
import io.github.realmlabs.asteria.protocol.protobuf.ProtoFrame
import io.github.realmlabs.asteria.protocol.protobuf.ProtobufProtocolRegistry
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageCodec

/**
 * Netty codec between [BinaryGatewayPacket] and protobuf messages.
 *
 * Inbound packets are decoded to [ClientProtoEnvelope] and direction-checked by [ProtobufProtocolRegistry]. Outbound
 * protobuf messages are encoded with their server-to-client protocol id.
 */
@Sharable
class NettyProtobufCodec(
    private val registry: ProtobufProtocolRegistry,
) : MessageToMessageCodec<BinaryGatewayPacket, GeneratedMessage>() {
    override fun encode(ctx: ChannelHandlerContext, msg: GeneratedMessage, out: MutableList<Any>) {
        val frame = registry.encode(msg)
        out.add(BinaryGatewayPacket(frame.id, frame.payload))
    }

    override fun decode(ctx: ChannelHandlerContext, msg: BinaryGatewayPacket, out: MutableList<Any>) {
        val envelope: ClientProtoEnvelope = registry.decode(ProtoFrame(msg.messageId, msg.payload))
        out.add(envelope)
    }
}
