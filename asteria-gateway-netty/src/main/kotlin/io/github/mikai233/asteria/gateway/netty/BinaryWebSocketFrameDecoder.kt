package io.github.mikai233.asteria.gateway.netty

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageDecoder
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame

class BinaryWebSocketFrameDecoder : MessageToMessageDecoder<BinaryWebSocketFrame>() {
    override fun decode(ctx: ChannelHandlerContext, msg: BinaryWebSocketFrame, out: MutableList<Any>) {
        out.add(msg.content().retain() as ByteBuf)
    }
}
