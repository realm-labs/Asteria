package io.github.mikai233.asteria.gateway.netty

import io.github.mikai233.asteria.gateway.GatewayConnectionId
import io.github.mikai233.asteria.gateway.GatewaySession
import io.github.mikai233.asteria.gateway.GatewayTransportHandler
import io.github.mikai233.asteria.gateway.GatewayTransportKind
import io.github.mikai233.asteria.observability.MetricTags
import io.github.mikai233.asteria.observability.Metrics
import io.github.mikai233.asteria.observability.NoopMetrics
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Netty handler that owns gateway session lifecycle but lets the application receive native decoded Netty messages.
 *
 * Use this when the business project wants to build its own Netty pipeline and decode directly into a packet/envelope
 * type instead of going through [io.github.mikai233.asteria.gateway.GatewayFrame]. This handler disables Netty
 * auto-release because delivery is coroutine based. If [I] is a reference-counted Netty object, the application receiver
 * owns releasing it.
 */
class NettyGatewayMessageHandler<I : Any>(
    inboundType: Class<out I>,
    private val transport: GatewayTransportKind,
    private val scope: CoroutineScope,
    private val handler: GatewayTransportHandler,
    private val receiver: NettyGatewayMessageReceiver<I>,
    private val connectionIdFactory: () -> GatewayConnectionId = {
        GatewayConnectionId(UUID.randomUUID().toString())
    },
    private val metrics: Metrics = NoopMetrics,
    private val writer: NettyGatewayFrameWriter = NettyGatewayFrameWriters.BYTE_BUF,
) : SimpleChannelInboundHandler<I>(inboundType, false) {
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

    override fun channelRead0(ctx: ChannelHandlerContext, msg: I) {
        val tags = transport.metricTags()
        metrics.counter("asteria.gateway.netty.message.inbound.total", tags).increment()
        val activeSession = session ?: run {
            metrics.counter("asteria.gateway.netty.message.dropped.no_session.total", tags).increment()
            ctx.close()
            return
        }
        scope.launch {
            runCatching {
                receiver.receive(
                    NettyGatewayMessageContext(
                        context = ctx,
                        session = activeSession.await(),
                        message = msg,
                    ),
                )
            }.onFailure {
                metrics.counter("asteria.gateway.netty.message.failed.total", tags).increment()
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

fun interface NettyGatewayMessageReceiver<I : Any> {
    suspend fun receive(context: NettyGatewayMessageContext<I>)
}

data class NettyGatewayMessageContext<I : Any>(
    val context: ChannelHandlerContext,
    val session: GatewaySession,
    val message: I,
)

private fun GatewayTransportKind.metricTags(): MetricTags {
    return MetricTags.of("transport" to name)
}
