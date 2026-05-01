package io.github.mikai233.asteria.rpc

import java.util.ServiceLoader

fun interface RpcEntityIdRegistryProvider {
    fun create(): RpcEntityIdRegistry
}

object RpcEntityIdRegistries {
    fun load(classLoader: ClassLoader = Thread.currentThread().contextClassLoader): RpcEntityIdRegistry {
        val registries = ServiceLoader
            .load(RpcEntityIdRegistryProvider::class.java, classLoader)
            .map { it.create() }
            .toList()
        return CompositeRpcEntityIdRegistry(registries)
    }
}
