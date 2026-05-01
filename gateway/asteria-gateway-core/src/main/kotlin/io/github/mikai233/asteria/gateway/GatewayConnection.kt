package io.github.mikai233.asteria.gateway

import java.net.SocketAddress

@JvmInline
value class GatewayConnectionId(val value: String)

@JvmInline
value class GatewaySessionId(val value: String)

enum class GatewayTransportKind {
    TCP,
    KCP,
    WEBSOCKET,
    CUSTOM,
}

/**
 * Transport-level connection exposed to gateway code.
 *
 * TCP, KCP and WebSocket implementations should adapt their native channel/session objects to this interface. Business
 * protocol details are intentionally not represented here; those live in [GatewayPacketCodec].
 */
interface GatewayConnection {
    val id: GatewayConnectionId
    val transport: GatewayTransportKind
    val remoteAddress: SocketAddress?

    fun write(frame: GatewayFrame)

    fun close()
}

/**
 * One complete binary frame ready to write to a transport.
 *
 * For TCP this usually means bytes after packet encoding but before length-field framing. For WebSocket this maps to one
 * binary message. For KCP this maps to one KCP payload.
 */
data class GatewayFrame(
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GatewayFrame

        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()
}
