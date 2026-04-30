package io.github.mikai233.asteria.rpc

import java.util.ServiceLoader

fun interface RpcRouteRegistryProvider {
    fun create(): RpcRouteRegistry
}

object RpcRouteRegistries {
    fun load(classLoader: ClassLoader = Thread.currentThread().contextClassLoader): RpcRouteRegistry {
        val registries = ServiceLoader
            .load(RpcRouteRegistryProvider::class.java, classLoader)
            .map { it.create() }
            .toList()
        return CompositeRpcRouteRegistry(registries)
    }
}
