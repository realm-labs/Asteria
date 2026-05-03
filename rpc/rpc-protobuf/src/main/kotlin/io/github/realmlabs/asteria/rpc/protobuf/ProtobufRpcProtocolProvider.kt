package io.github.realmlabs.asteria.rpc.protobuf

import io.github.realmlabs.asteria.rpc.RpcProtocol
import io.github.realmlabs.asteria.rpc.RpcProtocolProvider

/**
 * Base class for generated protobuf RPC protocol contributors.
 */
abstract class GeneratedProtobufRpcProtocol : RpcProtocolProvider, ProtobufRpcProtocolContributor {
    final override fun create(): RpcProtocol {
        return protobufRpcProtocol {
            contribute(this)
        }.rpcProtocol()
    }
}

object ProtobufRpcProtocols {
    fun load(classLoader: ClassLoader = Thread.currentThread().contextClassLoader): ProtobufRpcProtocol {
        return protobufRpcProtocol {
            java.util.ServiceLoader
                .load(ProtobufRpcProtocolContributor::class.java, classLoader)
                .forEach(::include)
        }
    }
}
