package io.github.realmlabs.asteria.gateway

/**
 * Gateway session lifecycle hooks.
 *
 * These hooks are intentionally transport/session level only. Login, player binding and reconnect policy belong to the
 * application layer and can be implemented by storing typed attributes on [GatewaySession].
 *
 * Hook methods run in the order chosen by the caller. The default transport/session helpers in this module call them in
 * the sequence `connected`, `beforeClose`, `disconnected`, `afterClose`.
 */
interface GatewaySessionLifecycle {
    suspend fun connected(context: GatewaySessionContext) = Unit

    suspend fun beforeClose(context: GatewaySessionContext, reason: GatewayCloseReason) = Unit

    suspend fun disconnected(context: GatewaySessionContext, reason: GatewayCloseReason) = Unit

    suspend fun afterClose(context: GatewaySessionContext, reason: GatewayCloseReason) = Unit
}

object NoopGatewaySessionLifecycle : GatewaySessionLifecycle

/**
 * Fan-out lifecycle that invokes several lifecycle listeners sequentially.
 */
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

/**
 * High-level closer that applies lifecycle hooks around registry removal and session close.
 */
class GatewaySessionController(
    private val sessions: GatewaySessionRegistry,
    private val lifecycle: GatewaySessionLifecycle = NoopGatewaySessionLifecycle,
) {
    /**
     * Closes and unregisters one session.
     *
     * Returns `false` when the session is already absent from the registry.
     */
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
