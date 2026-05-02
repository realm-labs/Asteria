package io.github.mikai233.asteria.gateway.netty

import io.github.mikai233.asteria.gateway.GatewayFrame
import io.github.mikai233.asteria.gateway.GatewayServerTransport
import io.github.mikai233.asteria.gateway.GatewayTransportHandler
import io.github.mikai233.asteria.gateway.GatewayTransportKind
import io.github.mikai233.asteria.observability.MetricTags
import io.github.mikai233.asteria.observability.Metrics
import io.github.mikai233.asteria.observability.NoopMetrics
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.ServerChannel
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollIoHandler
import io.netty.channel.epoll.EpollServerSocketChannel
import io.netty.channel.kqueue.KQueue
import io.netty.channel.kqueue.KQueueIoHandler
import io.netty.channel.kqueue.KQueueServerSocketChannel
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
import org.slf4j.LoggerFactory

data class NettyGatewayServerOptions(
    val host: String = "0.0.0.0",
    val port: Int,
    val bossThreads: Int = 1,
    val workerThreads: Int = 0,
    val maxFrameLength: Int = 1024 * 1024,
    val websocketPath: String = "/gateway",
    val eventLoopBackend: NettyEventLoopBackend = NettyEventLoopBackend.AUTO,
    val serverChannelOptions: NettyServerChannelOptions = NettyServerChannelOptions(),
    val childChannelOptions: NettyChildChannelOptions = NettyChildChannelOptions(),
)

data class NettyServerChannelOptions(
    val backlog: Int? = null,
    val reuseAddress: Boolean? = null,
    val receiveBufferSize: Int? = null,
    val extraOptions: List<NettyChannelOption<*>> = emptyList(),
)

data class NettyChildChannelOptions(
    val tcpNoDelay: Boolean? = true,
    val keepAlive: Boolean? = null,
    val reuseAddress: Boolean? = null,
    val receiveBufferSize: Int? = null,
    val sendBufferSize: Int? = null,
    val writeBufferWaterMark: NettyWriteBufferWaterMark? = null,
    val autoRead: Boolean? = null,
    val extraOptions: List<NettyChannelOption<*>> = emptyList(),
)

data class NettyWriteBufferWaterMark(
    val low: Int,
    val high: Int,
)

data class NettyChannelOption<T : Any>(
    val option: ChannelOption<T>,
    val value: T,
)

enum class NettyEventLoopBackend {
    /**
     * Uses epoll when available, then kqueue when available, otherwise NIO.
     */
    AUTO,

    NIO,
    EPOLL,
    KQUEUE,
}

abstract class NettyGatewayServerTransport(
    final override val kind: GatewayTransportKind,
    protected val options: NettyGatewayServerOptions,
    protected val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    protected val metrics: Metrics = NoopMetrics,
) : GatewayServerTransport {
    private val logger = LoggerFactory.getLogger(NettyGatewayServerTransport::class.java)
    private var bossGroup: EventLoopGroup? = null
    private var workerGroup: EventLoopGroup? = null
    private var channel: Channel? = null

    final override suspend fun start(handler: GatewayTransportHandler) {
        check(channel == null) { "gateway transport $kind is already started" }
        val startedAt = System.nanoTime()
        val eventLoop = NettyEventLoopResources.create(options)
        val tags = metricTags(eventLoop.backend)
        metrics.counter("asteria.gateway.netty.start.total", tags).increment()
        try {
            bossGroup = eventLoop.bossGroup
            workerGroup = eventLoop.workerGroup
            channel = ServerBootstrap()
                .group(eventLoop.bossGroup, eventLoop.workerGroup)
                .channel(eventLoop.serverChannelType)
                .applyOptions(options)
                .childHandler(initializer(handler))
                .bind(options.host, options.port)
                .syncUninterruptibly()
                .channel()
            metrics.counter("asteria.gateway.netty.start.succeeded.total", tags).increment()
            logger.info(
                "Netty gateway transport started kind={} host={} port={} backend={}",
                kind.name,
                options.host,
                options.port,
                eventLoop.backend.name,
            )
        } catch (error: Throwable) {
            metrics.counter("asteria.gateway.netty.start.failed.total", tags).increment()
            logger.error(
                "Netty gateway transport failed to start kind={} host={} port={} backend={}",
                kind.name,
                options.host,
                options.port,
                eventLoop.backend.name,
                error,
            )
            stop()
            throw error
        } finally {
            metrics.timer("asteria.gateway.netty.start.duration", tags)
                .record((System.nanoTime() - startedAt) / 1_000_000)
        }
    }

    final override suspend fun stop() {
        val startedAt = System.nanoTime()
        val tags = metricTags()
        metrics.counter("asteria.gateway.netty.stop.total", tags).increment()
        try {
            channel?.close()?.syncUninterruptibly()
            channel = null
            workerGroup?.shutdownGracefully()?.syncUninterruptibly()
            bossGroup?.shutdownGracefully()?.syncUninterruptibly()
            workerGroup = null
            bossGroup = null
            metrics.counter("asteria.gateway.netty.stop.succeeded.total", tags).increment()
            logger.info("Netty gateway transport stopped kind={} host={} port={}", kind.name, options.host, options.port)
        } catch (error: Throwable) {
            metrics.counter("asteria.gateway.netty.stop.failed.total", tags).increment()
            logger.error("Netty gateway transport failed to stop kind={} host={} port={}", kind.name, options.host, options.port, error)
            throw error
        } finally {
            metrics.timer("asteria.gateway.netty.stop.duration", tags)
                .record((System.nanoTime() - startedAt) / 1_000_000)
        }
    }

    final override fun close() {
        channel?.close()
        workerGroup?.shutdownGracefully()
        bossGroup?.shutdownGracefully()
    }

    protected abstract fun initializer(handler: GatewayTransportHandler): ChannelInitializer<SocketChannel>

    protected fun metricTags(backend: NettyEventLoopBackend? = null): MetricTags {
        return MetricTags.of(
            "transport" to kind.name,
            "backend" to (backend?.name ?: options.eventLoopBackend.name),
        )
    }
}

private fun ServerBootstrap.applyOptions(options: NettyGatewayServerOptions): ServerBootstrap {
    val server = options.serverChannelOptions
    server.backlog?.let { option(ChannelOption.SO_BACKLOG, it) }
    server.reuseAddress?.let { option(ChannelOption.SO_REUSEADDR, it) }
    server.receiveBufferSize?.let { option(ChannelOption.SO_RCVBUF, it) }
    server.extraOptions.forEach(::optionAny)

    val child = options.childChannelOptions
    child.tcpNoDelay?.let { childOption(ChannelOption.TCP_NODELAY, it) }
    child.keepAlive?.let { childOption(ChannelOption.SO_KEEPALIVE, it) }
    child.reuseAddress?.let { childOption(ChannelOption.SO_REUSEADDR, it) }
    child.receiveBufferSize?.let { childOption(ChannelOption.SO_RCVBUF, it) }
    child.sendBufferSize?.let { childOption(ChannelOption.SO_SNDBUF, it) }
    child.writeBufferWaterMark?.let {
        childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, io.netty.channel.WriteBufferWaterMark(it.low, it.high))
    }
    child.autoRead?.let { childOption(ChannelOption.AUTO_READ, it) }
    child.extraOptions.forEach(::childOptionAny)
    return this
}

private fun ServerBootstrap.optionAny(option: NettyChannelOption<*>) {
    @Suppress("UNCHECKED_CAST")
    option(option.option as ChannelOption<Any>, option.value)
}

private fun ServerBootstrap.childOptionAny(option: NettyChannelOption<*>) {
    @Suppress("UNCHECKED_CAST")
    childOption(option.option as ChannelOption<Any>, option.value)
}

private data class NettyEventLoopResources(
    val bossGroup: EventLoopGroup,
    val workerGroup: EventLoopGroup,
    val serverChannelType: Class<out ServerChannel>,
    val backend: NettyEventLoopBackend,
) {
    companion object {
        fun create(options: NettyGatewayServerOptions): NettyEventLoopResources {
            return when (val backend = resolve(options.eventLoopBackend)) {
                NettyEventLoopBackend.NIO -> NettyEventLoopResources(
                    bossGroup = MultiThreadIoEventLoopGroup(options.bossThreads, NioIoHandler.newFactory()),
                    workerGroup = MultiThreadIoEventLoopGroup(options.workerThreads, NioIoHandler.newFactory()),
                    serverChannelType = NioServerSocketChannel::class.java,
                    backend = backend,
                )

                NettyEventLoopBackend.EPOLL -> NettyEventLoopResources(
                    bossGroup = MultiThreadIoEventLoopGroup(options.bossThreads, EpollIoHandler.newFactory()),
                    workerGroup = MultiThreadIoEventLoopGroup(options.workerThreads, EpollIoHandler.newFactory()),
                    serverChannelType = EpollServerSocketChannel::class.java,
                    backend = backend,
                )

                NettyEventLoopBackend.KQUEUE -> NettyEventLoopResources(
                    bossGroup = MultiThreadIoEventLoopGroup(options.bossThreads, KQueueIoHandler.newFactory()),
                    workerGroup = MultiThreadIoEventLoopGroup(options.workerThreads, KQueueIoHandler.newFactory()),
                    serverChannelType = KQueueServerSocketChannel::class.java,
                    backend = backend,
                )

                NettyEventLoopBackend.AUTO -> error("unresolved Netty event loop backend $backend")
            }
        }

        private fun resolve(backend: NettyEventLoopBackend): NettyEventLoopBackend {
            return when (backend) {
                NettyEventLoopBackend.AUTO -> when {
                    Epoll.isAvailable() -> NettyEventLoopBackend.EPOLL
                    KQueue.isAvailable() -> NettyEventLoopBackend.KQUEUE
                    else -> NettyEventLoopBackend.NIO
                }

                NettyEventLoopBackend.EPOLL -> {
                    Epoll.ensureAvailability()
                    NettyEventLoopBackend.EPOLL
                }

                NettyEventLoopBackend.KQUEUE -> {
                    KQueue.ensureAvailability()
                    NettyEventLoopBackend.KQUEUE
                }

                NettyEventLoopBackend.NIO -> NettyEventLoopBackend.NIO
            }
        }
    }
}

class NettyTcpGatewayServerTransport(
    options: NettyGatewayServerOptions,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    metrics: Metrics = NoopMetrics,
) : NettyGatewayServerTransport(GatewayTransportKind.TCP, options, scope, metrics) {
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
                            metrics = metrics,
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
    metrics: Metrics = NoopMetrics,
) : NettyGatewayServerTransport(GatewayTransportKind.WEBSOCKET, options, scope, metrics) {
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
                            metrics = metrics,
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
