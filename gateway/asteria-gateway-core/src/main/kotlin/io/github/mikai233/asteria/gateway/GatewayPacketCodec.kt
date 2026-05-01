package io.github.mikai233.asteria.gateway

/**
 * Converts between complete transport frames and business packets.
 *
 * Applications commonly have their own packet header: message id, request sequence, flags, compression marker, and so on.
 * The gateway core only requires this boundary. Encryption, compression and signatures can be implemented either inside
 * an application codec or as [GatewayPacketInterceptor] instances around it.
 */
interface GatewayPacketCodec<P : Any> {
    fun decode(frame: GatewayFrame): P

    fun encode(packet: P): GatewayFrame
}

/**
 * Optional packet pipeline hook for cross-cutting protocol work.
 *
 * Use this for generic packet checks, metrics tags, compression, or application-owned encryption. The framework does not
 * define a concrete encryption/signature format because those formats are usually game-specific.
 */
interface GatewayPacketInterceptor<P : Any> {
    fun inbound(context: GatewaySessionContext, packet: P): P = packet

    fun outbound(context: GatewaySessionContext, packet: P): P = packet
}

class GatewayPacketPipeline<P : Any>(
    private val codec: GatewayPacketCodec<P>,
    interceptors: Iterable<GatewayPacketInterceptor<P>> = emptyList(),
) {
    private val interceptors: List<GatewayPacketInterceptor<P>> = interceptors.toList()

    fun decode(context: GatewaySessionContext, frame: GatewayFrame): P {
        return interceptors.fold(codec.decode(frame)) { packet, interceptor ->
            interceptor.inbound(context, packet)
        }
    }

    fun encode(context: GatewaySessionContext, packet: P): GatewayFrame {
        val intercepted = interceptors.asReversed().fold(packet) { current, interceptor ->
            interceptor.outbound(context, current)
        }
        return codec.encode(intercepted)
    }
}
