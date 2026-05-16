package io.github.realmlabs.asteria.gateway

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.CancellationException

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
 *
 * Listener failures are logged and do not stop later listeners from running.
 */
class CompositeGatewaySessionLifecycle(
    lifecycles: Iterable<GatewaySessionLifecycle> = emptyList(),
) : GatewaySessionLifecycle {
    private val logger = LoggerFactory.getLogger(CompositeGatewaySessionLifecycle::class.java)
    private val lifecycles: List<GatewaySessionLifecycle> = lifecycles.toList()

    override suspend fun connected(context: GatewaySessionContext) {
        lifecycles.forEach { lifecycle ->
            runGatewayLifecycleHook(logger, context, lifecycle.hookName("connected")) {
                lifecycle.connected(context)
            }
        }
    }

    override suspend fun beforeClose(context: GatewaySessionContext, reason: GatewayCloseReason) {
        lifecycles.forEach { lifecycle ->
            runGatewayLifecycleHook(logger, context, lifecycle.hookName("beforeClose")) {
                lifecycle.beforeClose(context, reason)
            }
        }
    }

    override suspend fun disconnected(context: GatewaySessionContext, reason: GatewayCloseReason) {
        lifecycles.forEach { lifecycle ->
            runGatewayLifecycleHook(logger, context, lifecycle.hookName("disconnected")) {
                lifecycle.disconnected(context, reason)
            }
        }
    }

    override suspend fun afterClose(context: GatewaySessionContext, reason: GatewayCloseReason) {
        lifecycles.forEach { lifecycle ->
            runGatewayLifecycleHook(logger, context, lifecycle.hookName("afterClose")) {
                lifecycle.afterClose(context, reason)
            }
        }
    }
}

/**
 * High-level closer that applies lifecycle hooks around registry removal and session close.
 */
class GatewaySessionController(
    private val sessions: GatewaySessionRegistry,
    private val lifecycle: GatewaySessionLifecycle = NoopGatewaySessionLifecycle,
) {
    private val logger = LoggerFactory.getLogger(GatewaySessionController::class.java)

    /**
     * Closes and unregisters one session.
     *
     * Returns `false` when the session is already absent from the registry.
     * Lifecycle hook failures are logged and do not prevent unregistering and closing the session.
     */
    suspend fun close(
        sessionId: GatewaySessionId,
        reason: GatewayCloseReason = GatewayCloseReason.Application,
    ): Boolean {
        val session = sessions.get(sessionId) ?: return false
        val context = GatewaySessionContext(session)
        runGatewayLifecycleHook(logger, context, "beforeClose") {
            lifecycle.beforeClose(context, reason)
        }
        val removed = sessions.unregister(sessionId) ?: return false
        removed.close(reason)
        runGatewayLifecycleHook(logger, context, "disconnected") {
            lifecycle.disconnected(context, reason)
        }
        runGatewayLifecycleHook(logger, context, "afterClose") {
            lifecycle.afterClose(context, reason)
        }
        return true
    }
}

internal suspend fun runGatewayLifecycleHook(
    logger: Logger,
    context: GatewaySessionContext,
    hook: String,
    block: suspend () -> Unit,
) {
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        logger.error("gateway session lifecycle hook failed session={} hook={}", context.session.id.value, hook, error)
    }
}

private fun GatewaySessionLifecycle.hookName(hook: String): String {
    val lifecycleName = this::class.qualifiedName ?: this::class.toString()
    return "$lifecycleName.$hook"
}
