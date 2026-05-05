package io.github.realmlabs.asteria.gateway

import java.net.SocketAddress

@JvmInline
value class GatewayConnectionId(val value: String)

@JvmInline
value class GatewaySessionId(val value: String)

/**
 * Transport family used by a gateway connection.
 */
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
 * protocol details are intentionally not represented here.
 */
interface GatewayConnection {
    val id: GatewayConnectionId
    val transport: GatewayTransportKind
    val remoteAddress: SocketAddress?

    /**
     * Writes one complete outbound frame.
     *
     * This is a synchronous transport-level API. Implementations may buffer internally, but write failures should
     * surface to the caller.
     */
    fun write(frame: GatewayFrame)

    /**
     * Closes the underlying transport connection.
     */
    fun close()
}

/**
 * One complete binary frame ready to write to a transport.
 *
 * This is only a lowest-common-denominator escape hatch for adapters that choose a binary-frame boundary. Richer network
 * stacks can keep their protocol objects in their own module and use [GatewaySession] only for shared session state.
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
