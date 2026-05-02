package io.github.mikai233.asteria.gateway.netty

import io.github.mikai233.asteria.gateway.GatewayConnection
import io.github.mikai233.asteria.gateway.GatewayConnectionId
import io.github.mikai233.asteria.gateway.GatewayFrame
import io.github.mikai233.asteria.gateway.GatewayTransportKind
import io.netty.channel.Channel
import java.net.SocketAddress

/**
 * Netty adapter for the transport-level gateway connection.
 */
class NettyGatewayConnection(
    override val id: GatewayConnectionId,
    override val transport: GatewayTransportKind,
    val channel: Channel,
    private val writer: NettyGatewayFrameWriter = NettyGatewayFrameWriters.BYTE_BUF,
) : GatewayConnection {
    override val remoteAddress: SocketAddress?
        get() = channel.remoteAddress()

    override fun write(frame: GatewayFrame) {
        writer.write(channel, frame)
    }

    override fun close() {
        channel.close()
    }
}
