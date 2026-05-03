package io.github.realmlabs.asteria.protocol.protobuf

/**
 * Creates gateway protobuf protocol metadata.
 */
fun interface ProtobufGatewayProtocolProvider {
    fun create(): ProtobufGatewayProtocol
}

/**
 * Base class for generated protobuf gateway protocol contributors.
 */
abstract class GeneratedProtobufGatewayProtocol : ProtobufGatewayProtocolProvider, ProtobufGatewayProtocolContributor {
    final override fun create(): ProtobufGatewayProtocol {
        return protobufGatewayProtocol {
            contribute(this)
        }
    }
}

object ProtobufGatewayProtocols {
    fun load(classLoader: ClassLoader = Thread.currentThread().contextClassLoader): ProtobufGatewayProtocol {
        return protobufGatewayProtocol {
            java.util.ServiceLoader
                .load(ProtobufGatewayProtocolContributor::class.java, classLoader)
                .forEach(::include)
        }
    }
}
