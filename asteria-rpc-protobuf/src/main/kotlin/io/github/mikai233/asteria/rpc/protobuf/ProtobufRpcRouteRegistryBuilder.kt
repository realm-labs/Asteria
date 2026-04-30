package io.github.mikai233.asteria.rpc.protobuf

import com.google.protobuf.GeneratedMessage
import io.github.mikai233.asteria.rpc.RpcTarget

class ProtobufRpcRouteRegistryBuilder {
    private val routes: MutableList<ProtobufRpcRoute<out GeneratedMessage>> = mutableListOf()

    inline fun <reified M : GeneratedMessage> route(noinline resolve: (M) -> RpcTarget) {
        route(M::class.java, resolve)
    }

    fun <M : GeneratedMessage> route(
        messageClass: Class<M>,
        resolve: (M) -> RpcTarget,
    ) {
        routes.add(ProtobufRpcRoute(messageClass, resolve))
    }

    fun build(): ProtobufRpcRouteRegistry {
        return ProtobufRpcRouteRegistry(routes)
    }
}

fun protobufRpcRouteRegistry(
    configure: ProtobufRpcRouteRegistryBuilder.() -> Unit,
): ProtobufRpcRouteRegistry {
    return ProtobufRpcRouteRegistryBuilder().apply(configure).build()
}
