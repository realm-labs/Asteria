package io.github.realmlabs.asteria.gateway.netty

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageDecoder
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame

/**
 * Retains the payload of a binary WebSocket frame and forwards it as a [ByteBuf].
 */
class BinaryWebSocketFrameDecoder : MessageToMessageDecoder<BinaryWebSocketFrame>() {
    override fun decode(ctx: ChannelHandlerContext, msg: BinaryWebSocketFrame, out: MutableList<Any>) {
        out.add(msg.content().retain() as ByteBuf)
    }
}
