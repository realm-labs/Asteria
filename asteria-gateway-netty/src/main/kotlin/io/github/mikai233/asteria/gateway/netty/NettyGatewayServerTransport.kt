package io.github.mikai233.asteria.gateway.netty

import io.github.mikai233.asteria.gateway.GatewayFrame
import io.github.mikai233.asteria.gateway.GatewayServerTransport
import io.github.mikai233.asteria.gateway.GatewayTransportHandler
import io.github.mikai233.asteria.gateway.GatewayTransportKind
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

data class NettyGatewayServerOptions(
    val host: String = "0.0.0.0",
    val port: Int,
    val bossThreads: Int = 1,
    val workerThreads: Int = 0,
    val maxFrameLength: Int = 1024 * 1024,
    val websocketPath: String = "/gateway",
)

abstract class NettyGatewayServerTransport(
    final override val kind: GatewayTransportKind,
    protected val options: NettyGatewayServerOptions,
    protected val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : GatewayServerTransport {
    private var bossGroup: EventLoopGroup? = null
    private var workerGroup: EventLoopGroup? = null
    private var channel: Channel? = null

    final override suspend fun start(handler: GatewayTransportHandler) {
        check(channel == null) { "gateway transport $kind is already started" }
        val boss = MultiThreadIoEventLoopGroup(options.bossThreads, NioIoHandler.newFactory())
        val worker = MultiThreadIoEventLoopGroup(options.workerThreads, NioIoHandler.newFactory())
        bossGroup = boss
        workerGroup = worker
        channel = ServerBootstrap()
            .group(boss, worker)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(initializer(handler))
            .bind(options.host, options.port)
            .syncUninterruptibly()
            .channel()
    }

    final override suspend fun stop() {
        channel?.close()?.syncUninterruptibly()
        channel = null
        workerGroup?.shutdownGracefully()?.syncUninterruptibly()
        bossGroup?.shutdownGracefully()?.syncUninterruptibly()
        workerGroup = null
        bossGroup = null
    }

    final override fun close() {
        channel?.close()
        workerGroup?.shutdownGracefully()
        bossGroup?.shutdownGracefully()
    }

    protected abstract fun initializer(handler: GatewayTransportHandler): ChannelInitializer<SocketChannel>
}

class NettyTcpGatewayServerTransport(
    options: NettyGatewayServerOptions,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : NettyGatewayServerTransport(GatewayTransportKind.TCP, options, scope) {
    override fun initializer(handler: GatewayTransportHandler): ChannelInitializer<SocketChannel> {
        return object : ChannelInitializer<SocketChannel>() {
            override fun initChannel(ch: SocketChannel) {
                ch.pipeline()
                    .addLast(LengthFieldBasedFrameDecoder(options.maxFrameLength, 0, Int.SIZE_BYTES, 0, Int.SIZE_BYTES))
                    .addLast(LengthFieldPrepender(Int.SIZE_BYTES))
                    .addLast(
                        NettyGatewayFrameHandler(
                            transport = GatewayTransportKind.TCP,
                            scope = scope,
                            handler = handler,
                            writer = ::writeByteBuf,
                        ),
                    )
            }
        }
    }
}

class NettyWebSocketGatewayServerTransport(
    options: NettyGatewayServerOptions,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : NettyGatewayServerTransport(GatewayTransportKind.WEBSOCKET, options, scope) {
    override fun initializer(handler: GatewayTransportHandler): ChannelInitializer<SocketChannel> {
        return object : ChannelInitializer<SocketChannel>() {
            override fun initChannel(ch: SocketChannel) {
                ch.pipeline()
                    .addLast(HttpServerCodec())
                    .addLast(HttpObjectAggregator(options.maxFrameLength))
                    .addLast(WebSocketServerProtocolHandler(options.websocketPath, null, true, options.maxFrameLength))
                    .addLast(BinaryWebSocketFrameDecoder())
                    .addLast(
                        NettyGatewayFrameHandler(
                            transport = GatewayTransportKind.WEBSOCKET,
                            scope = scope,
                            handler = handler,
                            writer = ::writeWebSocketFrame,
                        ),
                    )
            }
        }
    }
}

private fun writeByteBuf(channel: Channel, frame: GatewayFrame) {
    channel.writeAndFlush(Unpooled.wrappedBuffer(frame.bytes))
}

private fun writeWebSocketFrame(channel: Channel, frame: GatewayFrame) {
    channel.writeAndFlush(BinaryWebSocketFrame(Unpooled.wrappedBuffer(frame.bytes)))
}
