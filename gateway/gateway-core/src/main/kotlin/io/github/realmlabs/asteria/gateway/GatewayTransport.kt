package io.github.realmlabs.asteria.gateway

import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import org.slf4j.LoggerFactory

/**
 * Server-side transport adapter.
 *
 * TCP, KCP and WebSocket implementations own their native pipeline shape. The core only models lifecycle and complete
 * binary frame delivery for adapters that choose to use it.
 */
interface GatewayServerTransport : AutoCloseable {
    val kind: GatewayTransportKind

    /**
     * Starts accepting connections and delivering frames to [handler].
     */
    suspend fun start(handler: GatewayTransportHandler)

    /**
     * Stops the transport and releases its resources.
     */
    suspend fun stop()

    override fun close() {
        // Transport implementations with suspend shutdown should expose [stop] to their owner.
    }
}

/**
 * Transport callback interface used by [GatewayServerTransport].
 */
interface GatewayTransportHandler {
    /**
     * Creates and registers a session for a newly connected transport connection.
     */
    suspend fun connected(connection: GatewayConnection): GatewaySession

    /**
     * Delivers one received frame for a live session.
     */
    suspend fun received(session: GatewaySession, frame: GatewayFrame)

    /**
     * Notifies that the transport became inactive for [session].
     */
    suspend fun disconnected(session: GatewaySession, cause: Throwable? = null)
}

/**
 * Frame consumer used by [GatewaySessionTransportHandler] after session bookkeeping is done.
 */
fun interface GatewayFrameReceiver {
    suspend fun receive(context: GatewaySessionContext, frame: GatewayFrame)
}

/**
 * Default transport handler that only owns session registration and lifecycle.
 *
 * Packet decoding and protocol routing are intentionally supplied by [receiver], because those details are usually owned
 * by the concrete networking adapter or by game-specific protocol code.
 *
 * This handler updates session timestamps, registers and unregisters sessions, and drives lifecycle hooks. It does not
 * suppress receiver exceptions.
 */
class GatewaySessionTransportHandler(
    private val sessionFactory: (GatewayConnection) -> GatewaySession,
    private val sessions: GatewaySessionRegistry,
    private val receiver: GatewayFrameReceiver,
    private val lifecycle: GatewaySessionLifecycle = NoopGatewaySessionLifecycle,
    private val metrics: Metrics = NoopMetrics,
) : GatewayTransportHandler {
    private val logger = LoggerFactory.getLogger(GatewaySessionTransportHandler::class.java)

    /**
     * Creates a session, registers it, and invokes `connected` lifecycle hooks.
     *
     * Lifecycle hook failures are logged and do not reject the accepted session.
     */
    override suspend fun connected(connection: GatewayConnection): GatewaySession {
        val tags = connection.metricTags()
        metrics.counter("asteria.gateway.connection.connected.total", tags).increment()
        val session = sessionFactory(connection)
        sessions.register(session)
        val context = GatewaySessionContext(session)
        runGatewayLifecycleHook(logger, context, "connected") {
            lifecycle.connected(context)
        }
        logger.info(
            "gateway connected session={} connection={} transport={} remote={}",
            session.id.value,
            connection.id.value,
            connection.transport.name,
            connection.remoteAddress,
        )
        return session
    }

    /**
     * Marks the session as read and forwards the frame to [receiver].
     */
    override suspend fun received(session: GatewaySession, frame: GatewayFrame) {
        val tags = session.metricTags()
        val startedAt = System.nanoTime()
        metrics.counter("asteria.gateway.frame.received.total", tags).increment()
        session.markRead()
        try {
            receiver.receive(GatewaySessionContext(session), frame)
        } catch (error: Throwable) {
            metrics.counter("asteria.gateway.frame.failed.total", tags).increment()
            logger.error(
                "gateway frame handling failed session={} transport={}",
                session.id.value,
                session.transport.name,
                error,
            )
            throw error
        } finally {
            metrics.timer("asteria.gateway.frame.duration", tags)
                .record((System.nanoTime() - startedAt) / 1_000_000)
        }
    }

    /**
     * Unregisters the session and applies close lifecycle hooks.
     *
     * If the session was already absent from the registry, the callback becomes a no-op.
     * Lifecycle hook failures are logged and do not prevent session cleanup.
     */
    override suspend fun disconnected(session: GatewaySession, cause: Throwable?) {
        val removed = sessions.unregister(session.id) ?: return
        val reason = if (cause == null) {
            GatewayCloseReason.TransportInactive
        } else {
            GatewayCloseReason.error(cause)
        }
        val context = GatewaySessionContext(removed)
        runGatewayLifecycleHook(logger, context, "beforeClose") {
            lifecycle.beforeClose(context, reason)
        }
        removed.markClosed(reason)
        runGatewayLifecycleHook(logger, context, "disconnected") {
            lifecycle.disconnected(context, reason)
        }
        runGatewayLifecycleHook(logger, context, "afterClose") {
            lifecycle.afterClose(context, reason)
        }
        metrics.counter(
            "asteria.gateway.connection.disconnected.total",
            removed.metricTags() + MetricTags.of("reason" to reason.code),
        ).increment()
        logger.info(
            "gateway disconnected session={} transport={} reason={}",
            removed.id.value,
            removed.transport.name,
            reason.code,
        )
    }
}

private fun GatewayConnection.metricTags(): MetricTags {
    return MetricTags.of("transport" to transport.name)
}

private fun GatewaySession.metricTags(): MetricTags {
    return MetricTags.of("transport" to transport.name)
}
