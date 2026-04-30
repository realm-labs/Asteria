package io.github.mikai233.asteria.rpc

interface RpcRouteRegistry {
    fun resolve(message: Any): RpcTarget?
}

class CompositeRpcRouteRegistry(
    registries: Iterable<RpcRouteRegistry> = emptyList(),
) : RpcRouteRegistry {
    private val registries: List<RpcRouteRegistry> = registries.toList()

    override fun resolve(message: Any): RpcTarget? {
        return registries.firstNotNullOfOrNull { it.resolve(message) }
    }

    fun plus(registry: RpcRouteRegistry): CompositeRpcRouteRegistry {
        return CompositeRpcRouteRegistry(registries + registry)
    }

    fun all(): List<RpcRouteRegistry> = registries
}

class MissingRpcRouteException(message: Any) : IllegalArgumentException(
    "rpc route for ${message::class.qualifiedName} not found",
)
