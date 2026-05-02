package io.github.mikai233.asteria.gateway.netty

import io.github.mikai233.asteria.gateway.GatewayConnectionId
import io.github.mikai233.asteria.gateway.GatewayFrame
import io.github.mikai233.asteria.gateway.GatewayTransportHandler
import io.github.mikai233.asteria.gateway.GatewayTransportKind
import io.github.mikai233.asteria.observability.Metrics
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import kotlinx.coroutines.CoroutineScope

/**
 * Installs the Netty pipeline for one accepted gateway channel.
 *
 * This is the main escape hatch for business projects. The default installers below are small conveniences; projects
 * that need custom packet headers, compression, auth handshakes, encryption, KCP adapters or extra Netty handlers should
 * provide their own installer and decide where, or whether, to bridge into [NettyGatewayFrameHandler].
 */
fun interface NettyGatewayPipelineInstaller {
    fun install(channel: SocketChannel, context: NettyGatewayPipelineContext)
}

class NettyGatewayPipelineContext(
    val transport: GatewayTransportKind,
    val options: NettyGatewayServerOptions,
    val scope: CoroutineScope,
    val handler: GatewayTransportHandler,
    val metrics: Metrics,
    val connectionIdFactory: () -> GatewayConnectionId,
) {
    fun gatewayFrameHandler(writer: NettyGatewayFrameWriter): ChannelHandler {
        return NettyGatewayFrameHandler(
            transport = transport,
            scope = scope,
            handler = handler,
            connectionIdFactory = connectionIdFactory,
            metrics = metrics,
            writer = writer,
        )
    }

    fun <I : Any> gatewayMessageHandler(
        inboundType: Class<out I>,
        receiver: NettyGatewayMessageReceiver<I>,
        writer: NettyGatewayFrameWriter = NettyGatewayFrameWriters.BYTE_BUF,
    ): ChannelHandler {
        return NettyGatewayMessageHandler(
            inboundType = inboundType,
            transport = transport,
            scope = scope,
            handler = handler,
            receiver = receiver,
            connectionIdFactory = connectionIdFactory,
            metrics = metrics,
            writer = writer,
        )
    }
}

fun interface NettyGatewayFrameWriter {
    fun write(channel: Channel, frame: GatewayFrame)
}

object NettyGatewayFrameWriters {
    val BYTE_BUF: NettyGatewayFrameWriter = NettyGatewayFrameWriter { channel, frame ->
        channel.writeAndFlush(Unpooled.wrappedBuffer(frame.bytes))
    }

    val BINARY_WEBSOCKET_FRAME: NettyGatewayFrameWriter = NettyGatewayFrameWriter { channel, frame ->
        channel.writeAndFlush(BinaryWebSocketFrame(Unpooled.wrappedBuffer(frame.bytes)))
    }
}

object NettyGatewayPipelineInstallers {
    fun lengthFieldTcp(): NettyGatewayPipelineInstaller {
        return NettyGatewayPipelineInstaller { channel, context ->
            channel.pipeline()
                .addLast(
                    LengthFieldBasedFrameDecoder(
                        context.options.maxFrameLength,
                        0,
                        Int.SIZE_BYTES,
                        0,
                        Int.SIZE_BYTES
                    )
                )
                .addLast(LengthFieldPrepender(Int.SIZE_BYTES))
                .addLast(context.gatewayFrameHandler(NettyGatewayFrameWriters.BYTE_BUF))
        }
    }

    fun webSocket(): NettyGatewayPipelineInstaller {
        return NettyGatewayPipelineInstaller { channel, context ->
            channel.pipeline()
                .addLast(HttpServerCodec())
                .addLast(HttpObjectAggregator(context.options.maxFrameLength))
                .addLast(
                    WebSocketServerProtocolHandler(
                        context.options.websocketPath,
                        null,
                        true,
                        context.options.maxFrameLength
                    )
                )
                .addLast(BinaryWebSocketFrameDecoder())
                .addLast(context.gatewayFrameHandler(NettyGatewayFrameWriters.BINARY_WEBSOCKET_FRAME))
        }
    }
}
