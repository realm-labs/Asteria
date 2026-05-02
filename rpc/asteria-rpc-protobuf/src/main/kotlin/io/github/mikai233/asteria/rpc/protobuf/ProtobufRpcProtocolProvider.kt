package io.github.mikai233.asteria.rpc.protobuf

import io.github.mikai233.asteria.rpc.RpcProtocol
import io.github.mikai233.asteria.rpc.RpcProtocolProvider

/**
 * Base class for generated protobuf RPC protocol contributors.
 */
abstract class GeneratedProtobufRpcProtocol : RpcProtocolProvider, ProtobufRpcProtocolContributor {
    final override fun create(): RpcProtocol {
        return protobufRpcProtocol {
            contribute(this)
        }.protocol
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
