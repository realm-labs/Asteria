package io.github.mikai233.asteria.gateway

/**
 * Gateway session lifecycle hooks.
 *
 * These hooks are intentionally transport/session level only. Login, player binding and reconnect policy belong to the
 * application layer and can be implemented by storing typed attributes on [GatewaySession].
 */
interface GatewaySessionLifecycle {
    suspend fun connected(context: GatewaySessionContext) = Unit

    suspend fun beforeClose(context: GatewaySessionContext, reason: GatewayCloseReason) = Unit

    suspend fun disconnected(context: GatewaySessionContext, reason: GatewayCloseReason) = Unit

    suspend fun afterClose(context: GatewaySessionContext, reason: GatewayCloseReason) = Unit
}

object NoopGatewaySessionLifecycle : GatewaySessionLifecycle

class CompositeGatewaySessionLifecycle(
    lifecycles: Iterable<GatewaySessionLifecycle> = emptyList(),
) : GatewaySessionLifecycle {
    private val lifecycles: List<GatewaySessionLifecycle> = lifecycles.toList()

    override suspend fun connected(context: GatewaySessionContext) {
        lifecycles.forEach { it.connected(context) }
    }

    override suspend fun beforeClose(context: GatewaySessionContext, reason: GatewayCloseReason) {
        lifecycles.forEach { it.beforeClose(context, reason) }
    }

    override suspend fun disconnected(context: GatewaySessionContext, reason: GatewayCloseReason) {
        lifecycles.forEach { it.disconnected(context, reason) }
    }

    override suspend fun afterClose(context: GatewaySessionContext, reason: GatewayCloseReason) {
        lifecycles.forEach { it.afterClose(context, reason) }
    }
}

class GatewaySessionController(
    private val sessions: GatewaySessionRegistry,
    private val lifecycle: GatewaySessionLifecycle = NoopGatewaySessionLifecycle,
) {
    suspend fun close(
        sessionId: GatewaySessionId,
        reason: GatewayCloseReason = GatewayCloseReason.Application,
    ): Boolean {
        val session = sessions.get(sessionId) ?: return false
        val context = GatewaySessionContext(session)
        lifecycle.beforeClose(context, reason)
        val removed = sessions.unregister(sessionId) ?: return false
        removed.close(reason)
        lifecycle.disconnected(context, reason)
        lifecycle.afterClose(context, reason)
        return true
    }
}
