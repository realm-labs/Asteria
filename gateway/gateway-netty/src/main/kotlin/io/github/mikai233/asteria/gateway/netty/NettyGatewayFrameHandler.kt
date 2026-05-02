package io.github.mikai233.asteria.gateway.netty

import io.github.mikai233.asteria.gateway.GatewayConnectionId
import io.github.mikai233.asteria.gateway.GatewayFrame
import io.github.mikai233.asteria.gateway.GatewaySession
import io.github.mikai233.asteria.gateway.GatewayTransportHandler
import io.github.mikai233.asteria.gateway.GatewayTransportKind
import io.github.mikai233.asteria.observability.MetricTags
import io.github.mikai233.asteria.observability.Metrics
import io.github.mikai233.asteria.observability.NoopMetrics
import io.netty.buffer.ByteBuf
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
    private val metrics: Metrics = NoopMetrics,
    private val writer: NettyGatewayFrameWriter,
) : SimpleChannelInboundHandler<ByteBuf>() {
    private var session: Deferred<GatewaySession>? = null

    override fun channelActive(ctx: ChannelHandlerContext) {
        val connection = NettyGatewayConnection(
            id = connectionIdFactory(),
            transport = transport,
            channel = ctx.channel(),
            writer = writer,
        )
        metrics.counter("asteria.gateway.netty.channel.active.total", transport.metricTags()).increment()
        session = scope.async { handler.connected(connection) }
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
        val tags = transport.metricTags()
        metrics.counter("asteria.gateway.netty.frame.inbound.total", tags).increment()
        metrics.counter("asteria.gateway.netty.frame.inbound.bytes.total", tags).increment(msg.readableBytes().toLong())
        val bytes = ByteArray(msg.readableBytes())
        msg.readBytes(bytes)
        val activeSession = session ?: run {
            metrics.counter("asteria.gateway.netty.frame.dropped.no_session.total", tags).increment()
            ctx.close()
            return
        }
        scope.launch {
            runCatching { handler.received(activeSession.await(), GatewayFrame(bytes)) }
                .onFailure {
                    metrics.counter("asteria.gateway.netty.frame.failed.total", tags).increment()
                    handler.disconnected(activeSession.await(), it)
                    ctx.close()
                }
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        metrics.counter("asteria.gateway.netty.channel.inactive.total", transport.metricTags()).increment()
        val activeSession = session ?: return
        scope.launch {
            runCatching { handler.disconnected(activeSession.await()) }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        metrics.counter("asteria.gateway.netty.channel.exception.total", transport.metricTags()).increment()
        val activeSession = session
        if (activeSession != null) {
            scope.launch {
                runCatching { handler.disconnected(activeSession.await(), cause) }
            }
        }
        ctx.close()
    }
}

private fun GatewayTransportKind.metricTags(): MetricTags {
    return MetricTags.of("transport" to name)
}
