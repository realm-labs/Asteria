package io.github.mikai233.asteria.gateway.netty

import io.github.mikai233.asteria.gateway.GatewayConnectionId
import io.github.mikai233.asteria.gateway.GatewayFrame
import io.github.mikai233.asteria.gateway.GatewaySession
import io.github.mikai233.asteria.gateway.GatewayTransportHandler
import io.github.mikai233.asteria.gateway.GatewayTransportKind
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.UUID

class NettyGatewayFrameHandler(
    private val transport: GatewayTransportKind,
    private val scope: CoroutineScope,
    private val handler: GatewayTransportHandler,
    private val connectionIdFactory: () -> GatewayConnectionId = {
        GatewayConnectionId(UUID.randomUUID().toString())
    },
    private val writer: (Channel, GatewayFrame) -> Unit,
) : SimpleChannelInboundHandler<ByteBuf>() {
    private var session: Deferred<GatewaySession>? = null

    override fun channelActive(ctx: ChannelHandlerContext) {
        val connection = NettyGatewayConnection(
            id = connectionIdFactory(),
            transport = transport,
            channel = ctx.channel(),
            writer = writer,
        )
        session = scope.async { handler.connected(connection) }
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
        val bytes = ByteArray(msg.readableBytes())
        msg.readBytes(bytes)
        val activeSession = session ?: run {
            ctx.close()
            return
        }
        scope.launch {
            runCatching { handler.received(activeSession.await(), GatewayFrame(bytes)) }
                .onFailure {
                    handler.disconnected(activeSession.await(), it)
                    ctx.close()
                }
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        val activeSession = session ?: return
        scope.launch {
            runCatching { handler.disconnected(activeSession.await()) }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        val activeSession = session
        if (activeSession != null) {
            scope.launch {
                runCatching { handler.disconnected(activeSession.await(), cause) }
            }
        }
        ctx.close()
    }
}
