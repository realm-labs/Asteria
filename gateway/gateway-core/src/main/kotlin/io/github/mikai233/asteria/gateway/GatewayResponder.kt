package io.github.mikai233.asteria.gateway

/**
 * Writes outbound packets back to gateway sessions.
 *
 * Backend actors usually should not hold transport connections directly. They can keep the [GatewaySessionId] carried by an
 * inbound gateway envelope and call this responder when a business reply is ready. The responder owns packet encoding and
 * session lookup.
 */
fun interface GatewayResponder<P : Any> {
    /**
     * Encodes [packet] and writes it to [sessionId].
     *
     * Returns `false` when the session no longer exists. Implementations should let encoding or transport write failures
     * surface to the caller, because those failures usually indicate protocol or infrastructure problems.
     */
    suspend fun respond(sessionId: GatewaySessionId, packet: P): Boolean
}

/**
 * Default in-memory responder backed by a [GatewaySessionRegistry].
 */
class SessionGatewayResponder<P : Any>(
    private val sessions: GatewaySessionRegistry,
    private val packets: GatewayPacketPipeline<P>,
) : GatewayResponder<P> {
    override suspend fun respond(sessionId: GatewaySessionId, packet: P): Boolean {
        val session = sessions.get(sessionId) ?: return false
        val frame = packets.encode(GatewaySessionContext(session), packet)
        session.send(frame)
        return true
    }
}
