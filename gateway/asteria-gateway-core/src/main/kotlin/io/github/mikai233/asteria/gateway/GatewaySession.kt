package io.github.mikai233.asteria.gateway

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Mutable gateway session state bound to one client connection.
 */
class GatewaySession(
    val id: GatewaySessionId,
    val connection: GatewayConnection,
    val createdAt: Instant = Instant.now(),
) {
    private val attributes: MutableMap<GatewaySessionAttributeKey<*>, Any> = ConcurrentHashMap()

    val transport: GatewayTransportKind
        get() = connection.transport

    fun send(frame: GatewayFrame) {
        connection.write(frame)
    }

    fun close() {
        connection.close()
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
