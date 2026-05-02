package io.github.mikai233.asteria.rpc

import kotlin.reflect.KClass

data class RpcMethod<Req : Any, Resp : Any>(
    val id: Int,
    val name: String,
    val requestType: KClass<Req>,
    val responseType: KClass<Resp>?,
    val target: RpcTarget,
    val mode: RpcMode,
    val entityIdResolver: ((Req) -> String)? = null,
) {
    fun resolveEntityId(request: Any): String? {
        val resolver = entityIdResolver ?: return null
        require(requestType.isInstance(request)) {
            "rpc method $name cannot resolve entity id from ${request::class.qualifiedName}"
        }
        @Suppress("UNCHECKED_CAST")
        return (resolver as (Any) -> String)(request)
    }
}

interface RpcMethodRegistry {
    fun methodFor(id: Int): RpcMethod<*, *>?

    fun methodForRequest(requestType: KClass<*>): RpcMethod<*, *>?

    fun methodNamed(name: String): RpcMethod<*, *>?

    fun all(): Collection<RpcMethod<*, *>>
}

class StaticRpcMethodRegistry(
    methods: Iterable<RpcMethod<*, *>> = emptyList(),
) : RpcMethodRegistry {
    private val methodsById: Map<Int, RpcMethod<*, *>> = buildMap {
        methods.forEach { method ->
            val previous = put(method.id, method)
            check(previous == null) { "duplicate rpc method id ${method.id}" }
        }
    }
    private val methodsByRequestType: Map<KClass<*>, RpcMethod<*, *>> = buildMap {
        methods.forEach { method ->
            val previous = put(method.requestType, method)
            check(previous == null) {
                "duplicate rpc request type ${method.requestType.qualifiedName}"
            }
        }
    }
    private val methodsByName: Map<String, RpcMethod<*, *>> = buildMap {
        methods.forEach { method ->
            val previous = put(method.name, method)
            check(previous == null) { "duplicate rpc method name ${method.name}" }
        }
    }

    override fun methodFor(id: Int): RpcMethod<*, *>? = methodsById[id]

    override fun methodForRequest(requestType: KClass<*>): RpcMethod<*, *>? = methodsByRequestType[requestType]

    override fun methodNamed(name: String): RpcMethod<*, *>? = methodsByName[name]

    override fun all(): Collection<RpcMethod<*, *>> = methodsById.values
}
