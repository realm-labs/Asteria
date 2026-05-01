package io.github.mikai233.asteria.rpc

import io.github.mikai233.asteria.core.AsteriaModule
import io.github.mikai233.asteria.core.ModuleContext

class RpcModule private constructor(
    private val registryFactory: (ModuleContext) -> RpcEntityIdRegistry,
) : AsteriaModule {
    override val name: String = "rpc"

    override suspend fun install(context: ModuleContext) {
        context.services.register(RpcEntityIdRegistry::class, registryFactory(context))
    }

    companion object {
        fun autoDiscover(
            classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
        ): RpcModule {
            return RpcModule { RpcEntityIdRegistries.load(classLoader) }
        }

        fun withRegistry(registry: RpcEntityIdRegistry): RpcModule {
            return RpcModule { registry }
        }
    }
}
