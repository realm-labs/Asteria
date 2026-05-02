package io.github.mikai233.asteria.gateway

import io.github.mikai233.asteria.observability.MetricTags
import io.github.mikai233.asteria.observability.Metrics
import io.github.mikai233.asteria.observability.NoopMetrics
import org.slf4j.LoggerFactory

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
    private val metrics: Metrics = NoopMetrics,
) : GatewayTransportHandler {
    private val logger = LoggerFactory.getLogger(GatewayPipelineTransportHandler::class.java)

    override suspend fun connected(connection: GatewayConnection): GatewaySession {
        val tags = connection.metricTags()
        metrics.counter("asteria.gateway.connection.connected.total", tags).increment()
        val session = sessionFactory(connection)
        sessions.register(session)
        lifecycle.connected(GatewaySessionContext(session))
        logger.info(
            "gateway connected session={} connection={} transport={} remote={}",
            session.id.value,
            connection.id.value,
            connection.transport.name,
            connection.remoteAddress,
        )
        return session
    }

    override suspend fun received(session: GatewaySession, frame: GatewayFrame) {
        val tags = session.metricTags()
        val startedAt = System.nanoTime()
        metrics.counter("asteria.gateway.frame.received.total", tags).increment()
        session.markRead()
        try {
            inbound.receive(GatewaySessionContext(session), frame)
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
