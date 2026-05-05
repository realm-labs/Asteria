package io.github.realmlabs.asteria.message

import io.github.realmlabs.asteria.core.EntityKind
import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.core.SingletonName
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KClass

/**
 * Logical destination for a protocol message after route resolution.
 *
 * This is routing metadata only. Actual delivery is handled by transport or actor runtime modules.
 */
sealed interface RouteTarget {
    data class Entity(val kind: EntityKind) : RouteTarget
    data class Singleton(val name: SingletonName) : RouteTarget
    data class Service(val role: RoleKey, val path: String) : RouteTarget
    data object GatewayLocal : RouteTarget
}

/**
 * One message-type routing rule.
 *
 * [idResolver] is optional and is evaluated only by callers that need an entity or service key. The resolver is not
 * memoized and should therefore stay cheap and side-effect free.
 */
data class ProtocolRoute<M : Any>(
    val messageType: KClass<M>,
    val target: RouteTarget,
    val idResolver: ((M) -> Any?)? = null,
) {
    /**
     * Resolves a route id from [message] with an exact runtime type check.
     */
    fun resolveId(message: Any): Any? {
        val resolver = idResolver ?: return null
        require(messageType.isInstance(message)) {
            "route ${messageType.qualifiedName} cannot resolve id from ${message::class.qualifiedName}"
        }
        @Suppress("UNCHECKED_CAST")
        return (resolver as (Any) -> Any?)(message)
    }
}

/**
 * Immutable route registry keyed by exact message class.
 */
class RouteRegistry(
    routes: Iterable<ProtocolRoute<*>> = emptyList(),
) : ProtocolRouteRegistry {
    private val routesByMessageType: Map<KClass<*>, ProtocolRoute<*>> = protocolRoutesByMessageType(routes)

    override fun routeFor(messageType: KClass<*>): ProtocolRoute<*>? = routesByMessageType[messageType]

    override fun all(): Collection<ProtocolRoute<*>> = routesByMessageType.values
}

/**
 * Read-only route registry interface.
 */
interface ProtocolRouteRegistry {
    fun routeFor(messageType: KClass<*>): ProtocolRoute<*>?

    fun all(): Collection<ProtocolRoute<*>>
}

/**
 * Runtime-mutable route registry.
 *
 * Use this when a GM command or script needs to temporarily redirect a protocol message, for example from a world singleton
 * to player shard entities. Updates replace the complete route for one message type atomically, so each inbound packet sees
 * either the old route or the new route.
 *
 * Atomicity is per route entry only. There is no multi-message transaction across several route updates.
 */
class DynamicRouteRegistry(
    routes: Iterable<ProtocolRoute<*>> = emptyList(),
) : ProtocolRouteRegistry {
    private val routesByMessageType = AtomicReference(protocolRoutesByMessageType(routes))

    override fun routeFor(messageType: KClass<*>): ProtocolRoute<*>? {
        return routesByMessageType.get()[messageType]
    }

    override fun all(): Collection<ProtocolRoute<*>> {
        return routesByMessageType.get().values
    }

    /**
     * Replaces the route for [route.messageType] and returns the previous route if one existed.
     *
     * This mutates the live dynamic view directly. Unlike patch-layer replacement APIs, there is no separate base layer
     * retained inside [DynamicRouteRegistry] for automatic rollback.
     */
    fun replace(route: ProtocolRoute<*>): ProtocolRoute<*>? {
        while (true) {
            val current = routesByMessageType.get()
            val previous = current[route.messageType]
            val updated = current + (route.messageType to route)
            if (routesByMessageType.compareAndSet(current, updated)) {
                return previous
            }
        }
    }

    /**
     * Convenience overload that builds the [ProtocolRoute] from reified type information.
     */
    inline fun <reified M : Any> replace(
        target: RouteTarget,
        noinline idResolver: ((M) -> Any?)? = null,
    ): ProtocolRoute<*>? {
        return replace(ProtocolRoute(M::class, target, idResolver))
    }

    /**
     * Removes and returns the route for [messageType], or `null` when absent.
     *
     * Removal deletes the live entry entirely; subsequent lookups for the message type will miss until another route is
     * installed.
     */
    fun remove(messageType: KClass<*>): ProtocolRoute<*>? {
        while (true) {
            val current = routesByMessageType.get()
            val previous = current[messageType] ?: return null
            val updated = current - messageType
            if (routesByMessageType.compareAndSet(current, updated)) {
                return previous
            }
        }
    }

    inline fun <reified M : Any> remove(): ProtocolRoute<*>? = remove(M::class)

    /**
     * Returns an immutable snapshot of the current routes.
     *
     * The returned [RouteRegistry] is detached from future dynamic updates.
     */
    fun snapshot(): RouteRegistry = RouteRegistry(all())
}

/**
 * DSL builder for an immutable [RouteRegistry].
 */
class RouteRegistryBuilder {
    private val routes: MutableList<ProtocolRoute<*>> = mutableListOf()

    /**
     * Adds a route for one reified message type.
     */
    inline fun <reified M : Any> route(
        target: RouteTarget,
        noinline idResolver: ((M) -> Any?)? = null,
    ) {
        route(M::class, target, idResolver)
    }

    /**
     * Adds a route for [messageType].
     *
     * Duplicate message types are rejected when [build] is called.
     */
    fun <M : Any> route(
        messageType: KClass<M>,
        target: RouteTarget,
        idResolver: ((M) -> Any?)? = null,
    ) {
        routes.add(ProtocolRoute(messageType, target, idResolver))
    }

    fun build(): RouteRegistry = RouteRegistry(routes)
}

private fun protocolRoutesByMessageType(
    routes: Iterable<ProtocolRoute<*>>,
): Map<KClass<*>, ProtocolRoute<*>> = buildMap {
    routes.forEach { route ->
        val previous = put(route.messageType, route)
        check(previous == null) {
            "duplicate protocol route for ${route.messageType.qualifiedName}"
        }
    }
}
