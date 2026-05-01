package io.github.mikai233.asteria.message

import io.github.mikai233.asteria.core.EntityKind
import io.github.mikai233.asteria.core.RoleKey
import io.github.mikai233.asteria.core.SingletonName
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
)

class RouteRegistry(
    routes: Iterable<ProtocolRoute<*>> = emptyList(),
) {
    private val routesByMessageType: Map<KClass<*>, ProtocolRoute<*>> = routes.associateBy { it.messageType }

    fun routeFor(messageType: KClass<*>): ProtocolRoute<*>? = routesByMessageType[messageType]

    fun all(): Collection<ProtocolRoute<*>> = routesByMessageType.values
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
