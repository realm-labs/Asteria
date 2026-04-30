package io.github.mikai233.asteria.rpc

import io.github.mikai233.asteria.core.AsteriaModule
import io.github.mikai233.asteria.core.ModuleContext

class RpcModule private constructor(
    private val registryFactory: (ModuleContext) -> RpcRouteRegistry,
) : AsteriaModule {
    override val name: String = "rpc"

    override suspend fun install(context: ModuleContext) {
        context.services.register(RpcRouteRegistry::class, registryFactory(context))
    }

    companion object {
        fun autoDiscover(
            classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
        ): RpcModule {
            return RpcModule { RpcRouteRegistries.load(classLoader) }
        }

        fun withRegistry(registry: RpcRouteRegistry): RpcModule {
            return RpcModule { registry }
        }
    }
}
