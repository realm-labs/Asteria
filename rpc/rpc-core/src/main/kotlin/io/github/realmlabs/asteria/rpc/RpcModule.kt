package io.github.realmlabs.asteria.rpc

import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext

class RpcModule private constructor(
    private val protocolFactory: (ModuleContext) -> RpcProtocol,
) : AsteriaModule {
    override val name: String = "rpc"

    override suspend fun install(context: ModuleContext) {
        val protocol = protocolFactory(context)
        context.services.register(RpcProtocol::class, protocol)
        context.services.register(RpcMethodRegistry::class, protocol.methods)
        context.services.register(RpcEntityIdRegistry::class, protocol.entityIds)
    }

    companion object {
        fun autoDiscover(
            classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
        ): RpcModule {
            return RpcModule { RpcProtocols.load(classLoader) }
        }

        fun withProtocol(protocol: RpcProtocol): RpcModule {
            return RpcModule { protocol }
        }

        fun withRegistry(registry: RpcEntityIdRegistry): RpcModule {
            return RpcModule { RpcProtocol(entityIds = registry) }
        }
    }
}
