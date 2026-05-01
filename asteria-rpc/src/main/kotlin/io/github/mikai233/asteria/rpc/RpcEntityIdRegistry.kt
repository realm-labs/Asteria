package io.github.mikai233.asteria.rpc

interface RpcEntityIdRegistry {
    fun entityId(message: Any): String?
}

class CompositeRpcEntityIdRegistry(
    registries: Iterable<RpcEntityIdRegistry> = emptyList(),
) : RpcEntityIdRegistry {
    private val registries: List<RpcEntityIdRegistry> = registries.toList()

    override fun entityId(message: Any): String? {
        return registries.firstNotNullOfOrNull { it.entityId(message) }
    }

    fun plus(registry: RpcEntityIdRegistry): CompositeRpcEntityIdRegistry {
        return CompositeRpcEntityIdRegistry(registries + registry)
    }

    fun all(): List<RpcEntityIdRegistry> = registries
}

class MissingRpcEntityIdException(message: Any) : IllegalArgumentException(
    "rpc entity id for ${message::class.qualifiedName} not found",
)
