package io.github.mikai233.asteria.rpc

import java.util.*
import kotlin.reflect.KClass

fun interface RpcProtocolProvider {
    fun create(): RpcProtocol
}

object RpcProtocols {
    fun load(classLoader: ClassLoader = Thread.currentThread().contextClassLoader): RpcProtocol {
        val protocols = ServiceLoader
            .load(RpcProtocolProvider::class.java, classLoader)
            .map { it.create() }
            .toList()
        return compositeRpcProtocol(protocols)
    }
}

fun compositeRpcProtocol(protocols: Iterable<RpcProtocol>): RpcProtocol {
    val protocols = protocols.toList()
    return RpcProtocol(
        methods = CompositeRpcMethodRegistry(protocols.map { it.methods }),
        entityIds = CompositeRpcEntityIdRegistry(protocols.map { it.entityIds }),
    )
}

class CompositeRpcMethodRegistry(
    registries: Iterable<RpcMethodRegistry> = emptyList(),
) : RpcMethodRegistry {
    private val registries: List<RpcMethodRegistry> = registries.toList().also(::validateNoDuplicates)

    override fun methodFor(id: Int): RpcMethod<*, *>? = registries.firstNotNullOfOrNull { it.methodFor(id) }

    override fun methodForRequest(requestType: KClass<*>): RpcMethod<*, *>? {
        return registries.firstNotNullOfOrNull { it.methodForRequest(requestType) }
    }

    override fun methodNamed(name: String): RpcMethod<*, *>? = registries.firstNotNullOfOrNull { it.methodNamed(name) }

    override fun all(): Collection<RpcMethod<*, *>> = registries.flatMap { it.all() }

    private fun validateNoDuplicates(registries: List<RpcMethodRegistry>) {
        StaticRpcMethodRegistry(registries.flatMap { it.all() })
    }
}
