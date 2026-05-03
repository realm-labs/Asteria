package io.github.realmlabs.asteria.rpc

import kotlin.reflect.KClass

interface RpcEntityIdResolver<M : Any> {
    val messageType: KClass<M>

    fun entityId(message: M): String
}

class ResolverRpcEntityIdRegistry(
    resolvers: Iterable<RpcEntityIdResolver<*>> = emptyList(),
) : RpcEntityIdRegistry {
    private val resolversByType: Map<KClass<*>, RpcEntityIdResolver<*>> = buildMap {
        resolvers.forEach { resolver ->
            check(resolver.messageType !in this) {
                "duplicate rpc entity id resolver for ${resolver.messageType.qualifiedName}"
            }
            put(resolver.messageType, resolver)
        }
    }

    override fun entityId(message: Any): String? {
        val resolver = resolversByType[message::class] ?: return null
        return entityIdUnchecked(resolver, message)
    }

    private fun entityIdUnchecked(resolver: RpcEntityIdResolver<*>, message: Any): String {
        @Suppress("UNCHECKED_CAST")
        return (resolver as RpcEntityIdResolver<Any>).entityId(message)
    }
}
