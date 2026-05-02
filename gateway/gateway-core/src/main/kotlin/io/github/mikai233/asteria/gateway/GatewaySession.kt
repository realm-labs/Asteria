package io.github.mikai233.asteria.gateway

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Mutable gateway session state bound to one client connection.
 *
 * Session does not define login or player binding semantics. Applications can attach those through typed attributes.
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

    val state: GatewaySessionState
        get() = stateRef.get()

    val isOpen: Boolean
        get() = state == GatewaySessionState.OPEN

    fun send(frame: GatewayFrame) = write(frame)

    fun write(frame: GatewayFrame) {
        check(isOpen) { "gateway session ${id.value} is ${state.name.lowercase()}" }
        lastWriteAt = Instant.now()
        connection.write(frame)
    }

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

    fun markRead(now: Instant = Instant.now()) {
        lastReadAt = now
    }

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

    fun <T : Any> set(key: GatewaySessionAttributeKey<T>, value: T) {
        attributes[key] = value
    }

    fun <T : Any> get(key: GatewaySessionAttributeKey<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return attributes[key] as T?
    }

    fun remove(key: GatewaySessionAttributeKey<*>) {
        attributes.remove(key)
    }
}

enum class GatewaySessionState {
    OPEN,
    CLOSING,
    CLOSED,
}

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

@JvmInline
value class GatewaySessionAttributeKey<T : Any>(val name: String)

data class GatewaySessionContext(
    val session: GatewaySession,
)

interface GatewaySessionRegistry {
    fun register(session: GatewaySession)

    fun unregister(id: GatewaySessionId): GatewaySession?

    fun get(id: GatewaySessionId): GatewaySession?

    fun all(): Collection<GatewaySession>

    fun close(id: GatewaySessionId, reason: GatewayCloseReason = GatewayCloseReason.Application): GatewaySession? {
        val session = unregister(id) ?: return null
        session.close(reason)
        return session
    }
}

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

data class GatewayIdlePolicy(
    val readIdle: Duration? = null,
    val writeIdle: Duration? = null,
    val allIdle: Duration? = null,
)

enum class GatewayIdleKind {
    READ,
    WRITE,
    ALL,
}

data class GatewayIdleSession(
    val session: GatewaySession,
    val kind: GatewayIdleKind,
    val idleFor: Duration,
)

class GatewayIdleDetector(
    private val sessions: GatewaySessionRegistry,
    private val policy: GatewayIdlePolicy,
) {
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
