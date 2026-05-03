package io.github.realmlabs.asteria.message

import io.github.realmlabs.asteria.core.EntityKind
import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.core.SingletonName
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KClass

sealed interface RouteTarget {
    data class Entity(val kind: EntityKind) : RouteTarget
    data class Singleton(val name: SingletonName) : RouteTarget
    data class Service(val role: RoleKey, val path: String) : RouteTarget
    data object GatewayLocal : RouteTarget
}

data class ProtocolRoute<M : Any>(
    val messageType: KClass<M>,
    val target: RouteTarget,
    val idResolver: ((M) -> Any?)? = null,
) {
    fun resolveId(message: Any): Any? {
        val resolver = idResolver ?: return null
        require(messageType.isInstance(message)) {
            "route ${messageType.qualifiedName} cannot resolve id from ${message::class.qualifiedName}"
        }
        @Suppress("UNCHECKED_CAST")
        return (resolver as (Any) -> Any?)(message)
    }
}

class RouteRegistry(
    routes: Iterable<ProtocolRoute<*>> = emptyList(),
) : ProtocolRouteRegistry {
    private val routesByMessageType: Map<KClass<*>, ProtocolRoute<*>> = protocolRoutesByMessageType(routes)

    override fun routeFor(messageType: KClass<*>): ProtocolRoute<*>? = routesByMessageType[messageType]

    override fun all(): Collection<ProtocolRoute<*>> = routesByMessageType.values
}

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

    inline fun <reified M : Any> replace(
        target: RouteTarget,
        noinline idResolver: ((M) -> Any?)? = null,
    ): ProtocolRoute<*>? {
        return replace(ProtocolRoute(M::class, target, idResolver))
    }

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

    fun snapshot(): RouteRegistry = RouteRegistry(all())
}

class RouteRegistryBuilder {
    private val routes: MutableList<ProtocolRoute<*>> = mutableListOf()

    inline fun <reified M : Any> route(
        target: RouteTarget,
        noinline idResolver: ((M) -> Any?)? = null,
    ) {
        route(M::class, target, idResolver)
    }

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
