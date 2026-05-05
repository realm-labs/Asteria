package io.github.realmlabs.asteria.gateway

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Mutable gateway session state bound to one client connection.
 *
 * Session does not define login or player binding semantics. Applications can attach those through typed attributes.
 *
 * The session tracks transport activity timestamps and a small attribute bag, but it is not a durable player session
 * model by itself.
 */
class GatewaySession(
    val id: GatewaySessionId,
    val connection: GatewayConnection,
    val createdAt: Instant = Instant.now(),
) {
    private val attributes: MutableMap<GatewaySessionAttributeKey<*>, Any> = ConcurrentHashMap()
    private val stateRef: AtomicReference<GatewaySessionState> = AtomicReference(GatewaySessionState.OPEN)

    @Volatile
    var lastReadAt: Instant = createdAt
        private set

    @Volatile
    var lastWriteAt: Instant = createdAt
        private set

    @Volatile
    var closedAt: Instant? = null
        private set

    @Volatile
    var closeReason: GatewayCloseReason? = null
        private set

    val transport: GatewayTransportKind
        get() = connection.transport

    /**
     * Current local session state.
     */
    val state: GatewaySessionState
        get() = stateRef.get()

    /**
     * Returns `true` while the session still accepts writes.
     */
    val isOpen: Boolean
        get() = state == GatewaySessionState.OPEN

    /**
     * Alias of [write].
     */
    fun send(frame: GatewayFrame) = write(frame)

    /**
     * Writes one frame to the underlying connection.
     *
     * Writing to a non-open session throws immediately instead of silently dropping the frame.
     */
    fun write(frame: GatewayFrame) {
        check(isOpen) { "gateway session ${id.value} is ${state.name.lowercase()}" }
        lastWriteAt = Instant.now()
        connection.write(frame)
    }

    /**
     * Closes the session locally and closes the underlying connection.
     *
     * Returns `true` only for the first successful transition out of [GatewaySessionState.OPEN].
     */
    fun close(reason: GatewayCloseReason = GatewayCloseReason.Application): Boolean {
        if (!stateRef.compareAndSet(GatewaySessionState.OPEN, GatewaySessionState.CLOSING)) {
            return false
        }
        closeReason = reason
        closedAt = Instant.now()
        connection.close()
        stateRef.set(GatewaySessionState.CLOSED)
        return true
    }

    /**
     * Updates the last-read timestamp.
     */
    fun markRead(now: Instant = Instant.now()) {
        lastReadAt = now
    }

    /**
     * Marks the session closed without invoking [GatewayConnection.close].
     *
     * This is intended for transport callbacks that have already observed the underlying connection become inactive.
     */
    fun markClosed(
        reason: GatewayCloseReason,
        now: Instant = Instant.now(),
    ): Boolean {
        while (true) {
            val current = stateRef.get()
            if (current == GatewaySessionState.CLOSED) {
                return false
            }
            if (stateRef.compareAndSet(current, GatewaySessionState.CLOSED)) {
                closeReason = reason
                closedAt = now
                return true
            }
        }
    }

    /**
     * Stores one typed session attribute.
     */
    fun <T : Any> set(key: GatewaySessionAttributeKey<T>, value: T) {
        attributes[key] = value
    }

    /**
     * Returns a typed session attribute, or `null` when absent.
     */
    fun <T : Any> get(key: GatewaySessionAttributeKey<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return attributes[key] as T?
    }

    /**
     * Removes a session attribute.
     */
    fun remove(key: GatewaySessionAttributeKey<*>) {
        attributes.remove(key)
    }
}

/**
 * Local session state machine.
 */
enum class GatewaySessionState {
    OPEN,
    CLOSING,
    CLOSED,
}

/**
 * Reason associated with a session close event.
 */
data class GatewayCloseReason(
    val code: String,
    val message: String? = null,
    val cause: Throwable? = null,
) {
    companion object {
        val Application: GatewayCloseReason = GatewayCloseReason("application")
        val TransportInactive: GatewayCloseReason = GatewayCloseReason("transport_inactive")
        val IdleTimeout: GatewayCloseReason = GatewayCloseReason("idle_timeout")

        fun error(cause: Throwable): GatewayCloseReason {
            return GatewayCloseReason("error", cause.message, cause)
        }
    }
}

/**
 * Typed key for the session attribute bag.
 */
@JvmInline
value class GatewaySessionAttributeKey<T : Any>(val name: String)

/**
 * Minimal context passed to gateway packet routing and lifecycle hooks.
 */
data class GatewaySessionContext(
    val session: GatewaySession,
)

/**
 * Registry of currently live gateway sessions.
 *
 * Registry implementations own lookup and uniqueness only. They do not define authentication, player binding, or
 * distributed replication semantics.
 */
interface GatewaySessionRegistry {
    fun register(session: GatewaySession)

    fun unregister(id: GatewaySessionId): GatewaySession?

    fun get(id: GatewaySessionId): GatewaySession?

    fun all(): Collection<GatewaySession>

    /**
     * Unregisters and closes a session in one convenience call.
     */
    fun close(id: GatewaySessionId, reason: GatewayCloseReason = GatewayCloseReason.Application): GatewaySession? {
        val session = unregister(id) ?: return null
        session.close(reason)
        return session
    }
}

/**
 * Process-local concurrent [GatewaySessionRegistry].
 */
class LocalGatewaySessionRegistry : GatewaySessionRegistry {
    private val sessions: MutableMap<GatewaySessionId, GatewaySession> = ConcurrentHashMap()

    override fun register(session: GatewaySession) {
        val previous = sessions.putIfAbsent(session.id, session)
        check(previous == null) { "duplicate gateway session ${session.id.value}" }
    }

    override fun unregister(id: GatewaySessionId): GatewaySession? = sessions.remove(id)

    override fun get(id: GatewaySessionId): GatewaySession? = sessions[id]

    override fun all(): Collection<GatewaySession> = sessions.values.toList()
}

/**
 * Thresholds used by [GatewayIdleDetector].
 *
 * A `null` threshold disables that idle dimension.
 */
data class GatewayIdlePolicy(
    val readIdle: Duration? = null,
    val writeIdle: Duration? = null,
    val allIdle: Duration? = null,
)

/**
 * Type of idle condition detected for a session.
 */
enum class GatewayIdleKind {
    READ,
    WRITE,
    ALL,
}

/**
 * One idle-session report produced by [GatewayIdleDetector].
 */
data class GatewayIdleSession(
    val session: GatewaySession,
    val kind: GatewayIdleKind,
    val idleFor: Duration,
)

/**
 * Reports sessions that exceed one or more idle thresholds.
 *
 * Detection is observational only. This class never closes sessions by itself; callers decide what to do with the
 * reported idle sessions.
 */
class GatewayIdleDetector(
    private val sessions: GatewaySessionRegistry,
    private val policy: GatewayIdlePolicy,
) {
    /**
     * Returns all idle conditions currently observed across registered sessions.
     *
     * The same session may appear multiple times when it violates more than one threshold.
     */
    fun detect(now: Instant = Instant.now()): List<GatewayIdleSession> {
        return sessions.all().flatMap { session ->
            buildList {
                policy.readIdle?.let { threshold ->
                    val idleFor = Duration.between(session.lastReadAt, now)
                    if (!idleFor.minus(threshold).isNegative) {
                        add(GatewayIdleSession(session, GatewayIdleKind.READ, idleFor))
                    }
                }
                policy.writeIdle?.let { threshold ->
                    val idleFor = Duration.between(session.lastWriteAt, now)
                    if (!idleFor.minus(threshold).isNegative) {
                        add(GatewayIdleSession(session, GatewayIdleKind.WRITE, idleFor))
                    }
                }
                policy.allIdle?.let { threshold ->
                    val latestActivity = maxOf(session.lastReadAt, session.lastWriteAt)
                    val idleFor = Duration.between(latestActivity, now)
                    if (!idleFor.minus(threshold).isNegative) {
                        add(GatewayIdleSession(session, GatewayIdleKind.ALL, idleFor))
                    }
                }
            }
        }
    }
}
