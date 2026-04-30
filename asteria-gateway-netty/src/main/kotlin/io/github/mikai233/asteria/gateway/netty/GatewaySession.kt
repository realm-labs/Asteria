package io.github.mikai233.asteria.gateway.netty

import com.google.protobuf.GeneratedMessage
import io.netty.channel.Channel

@JvmInline
value class SessionId(val value: String)

interface GatewaySession {
    val id: SessionId
    val channel: Channel

    fun send(message: GeneratedMessage)

    fun close()
}

class NettyGatewaySession(
    override val id: SessionId,
    override val channel: Channel,
) : GatewaySession {
    override fun send(message: GeneratedMessage) {
        channel.writeAndFlush(message)
    }

    override fun close() {
        channel.close()
    }
}
