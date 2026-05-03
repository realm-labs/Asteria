package io.github.realmlabs.asteria.rpc.protobuf

/**
 * Base class for generated protobuf RPC protocol contributors.
 */
abstract class GeneratedProtobufRpcProtocol : ProtobufRpcProtocolContributor

object ProtobufRpcProtocols {
    fun load(classLoader: ClassLoader = Thread.currentThread().contextClassLoader): ProtobufRpcProtocol {
        return protobufRpcProtocol {
            java.util.ServiceLoader
                .load(ProtobufRpcProtocolContributor::class.java, classLoader)
                .forEach(::include)
        }
    }
}
