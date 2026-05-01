package io.github.mikai233.asteria.gateway

/**
 * Server-side transport adapter.
 *
 * TCP, KCP and WebSocket implementations should differ mostly here: how they bind, accept connections, and turn native
 * transport messages into complete [GatewayFrame] values.
 */
interface GatewayServerTransport : AutoCloseable {
    val kind: GatewayTransportKind

    suspend fun start(handler: GatewayTransportHandler)

    suspend fun stop()

    override fun close() {
        // Transport implementations with suspend shutdown should expose [stop] to their owner.
    }
}

interface GatewayTransportHandler {
    suspend fun connected(connection: GatewayConnection): GatewaySession

    suspend fun received(session: GatewaySession, frame: GatewayFrame)

    suspend fun disconnected(session: GatewaySession, cause: Throwable? = null)
}

/**
 * Default transport handler that registers sessions and forwards frames into an inbound pipeline.
 */
class GatewayPipelineTransportHandler<P : Any>(
    private val sessionFactory: (GatewayConnection) -> GatewaySession,
    private val sessions: GatewaySessionRegistry,
    private val inbound: GatewayInboundPipeline<P>,
) : GatewayTransportHandler {
    override suspend fun connected(connection: GatewayConnection): GatewaySession {
        val session = sessionFactory(connection)
        sessions.register(session)
        return session
    }

    override suspend fun received(session: GatewaySession, frame: GatewayFrame) {
        inbound.receive(GatewaySessionContext(session), frame)
    }

    override suspend fun disconnected(session: GatewaySession, cause: Throwable?) {
        sessions.unregister(session.id)
    }
}
