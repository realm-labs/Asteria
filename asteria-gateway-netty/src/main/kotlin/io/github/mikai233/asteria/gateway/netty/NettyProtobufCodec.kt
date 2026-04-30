package io.github.mikai233.asteria.gateway.netty

import com.google.protobuf.GeneratedMessage
import io.github.mikai233.asteria.protocol.protobuf.ClientProtoEnvelope
import io.github.mikai233.asteria.protocol.protobuf.ProtoFrame
import io.github.mikai233.asteria.protocol.protobuf.ProtobufProtocolRegistry
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageCodec

@Sharable
class NettyProtobufCodec(
    private val registry: ProtobufProtocolRegistry,
) : MessageToMessageCodec<ProtoFrame, GeneratedMessage>() {
    override fun encode(ctx: ChannelHandlerContext, msg: GeneratedMessage, out: MutableList<Any>) {
        out.add(registry.encode(msg))
    }

    override fun decode(ctx: ChannelHandlerContext, msg: ProtoFrame, out: MutableList<Any>) {
        val envelope: ClientProtoEnvelope = registry.decode(msg)
        out.add(envelope)
    }
}
