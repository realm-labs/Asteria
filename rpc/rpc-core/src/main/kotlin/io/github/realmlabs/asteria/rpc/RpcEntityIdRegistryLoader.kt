package io.github.realmlabs.asteria.rpc

import java.util.*

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
        entityIds = CompositeRpcEntityIdRegistry(protocols.map { it.entityIds }),
    )
}
