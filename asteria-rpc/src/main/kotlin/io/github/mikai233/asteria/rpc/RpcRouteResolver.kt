package io.github.mikai233.asteria.rpc

import kotlin.reflect.KClass

interface RpcRouteResolver<M : Any> {
    val messageType: KClass<M>

    fun resolve(message: M): RpcTarget
}

class ResolverRpcRouteRegistry(
    resolvers: Iterable<RpcRouteResolver<*>> = emptyList(),
) : RpcRouteRegistry {
    private val resolversByType: Map<KClass<*>, RpcRouteResolver<*>> = buildMap {
        resolvers.forEach { resolver ->
            check(resolver.messageType !in this) {
                "duplicate rpc route resolver for ${resolver.messageType.qualifiedName}"
            }
            put(resolver.messageType, resolver)
        }
    }

    override fun resolve(message: Any): RpcTarget? {
        val resolver = resolversByType[message::class] ?: return null
        return resolveUnchecked(resolver, message)
    }

    private fun resolveUnchecked(resolver: RpcRouteResolver<*>, message: Any): RpcTarget {
        @Suppress("UNCHECKED_CAST")
        return (resolver as RpcRouteResolver<Any>).resolve(message)
    }
}
