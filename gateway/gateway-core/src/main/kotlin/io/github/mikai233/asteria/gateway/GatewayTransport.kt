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
    private val lifecycle: GatewaySessionLifecycle = NoopGatewaySessionLifecycle,
) : GatewayTransportHandler {
    override suspend fun connected(connection: GatewayConnection): GatewaySession {
        val session = sessionFactory(connection)
        sessions.register(session)
        lifecycle.connected(GatewaySessionContext(session))
        return session
    }

    override suspend fun received(session: GatewaySession, frame: GatewayFrame) {
        session.markRead()
        inbound.receive(GatewaySessionContext(session), frame)
    }

    override suspend fun disconnected(session: GatewaySession, cause: Throwable?) {
        val removed = sessions.unregister(session.id) ?: return
        val reason = if (cause == null) {
            GatewayCloseReason.TransportInactive
        } else {
            GatewayCloseReason.error(cause)
        }
        val context = GatewaySessionContext(removed)
        lifecycle.beforeClose(context, reason)
        removed.markClosed(reason)
        lifecycle.disconnected(context, reason)
        lifecycle.afterClose(context, reason)
    }
}
