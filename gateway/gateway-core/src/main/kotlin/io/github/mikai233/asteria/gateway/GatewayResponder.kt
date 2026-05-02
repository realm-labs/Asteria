package io.github.mikai233.asteria.gateway

/**
 * Writes outbound packets back to gateway sessions.
 *
 * Backend actors usually should not hold transport connections directly. They can keep the [GatewaySessionId] carried by
 * an inbound gateway envelope and call this responder when a business reply is ready.
 */
fun interface GatewayResponder<P : Any> {
    /**
     * Writes [packet] to [sessionId].
     *
     * Returns `false` when the session no longer exists. Implementations should let protocol or transport write failures
     * surface to the caller, because those failures usually indicate infrastructure problems.
     */
    suspend fun respond(sessionId: GatewaySessionId, packet: P): Boolean
}

/**
 * In-memory responder backed by a [GatewaySessionRegistry].
 *
 * The concrete write function is supplied by the application or adapter. That keeps gateway-core free of packet encoding
 * assumptions.
 */
class SessionGatewayResponder<P : Any>(
    private val sessions: GatewaySessionRegistry,
    private val write: suspend (GatewaySession, P) -> Unit,
) : GatewayResponder<P> {
    override suspend fun respond(sessionId: GatewaySessionId, packet: P): Boolean {
        val session = sessions.get(sessionId) ?: return false
        write(session, packet)
        return true
    }
}
