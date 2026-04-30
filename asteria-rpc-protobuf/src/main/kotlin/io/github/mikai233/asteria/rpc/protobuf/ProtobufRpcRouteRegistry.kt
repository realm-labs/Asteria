package io.github.mikai233.asteria.rpc.protobuf

import com.google.protobuf.GeneratedMessage
import io.github.mikai233.asteria.rpc.RpcRouteRegistry
import io.github.mikai233.asteria.rpc.RpcTarget

class ProtobufRpcRouteRegistry(
    routes: Iterable<ProtobufRpcRoute<out GeneratedMessage>> = emptyList(),
) : RpcRouteRegistry {
    private val routesByType: Map<Class<out GeneratedMessage>, ProtobufRpcRoute<out GeneratedMessage>> = buildMap {
        routes.forEach { route ->
            check(route.messageClass !in this) {
                "duplicate protobuf rpc route for ${route.messageClass.name}"
            }
            put(route.messageClass, route)
        }
    }

    override fun resolve(message: Any): RpcTarget? {
        if (message !is GeneratedMessage) {
            return null
        }
        val route = routesByType[message.javaClass] ?: return null
        return resolveUnchecked(route, message)
    }

    private fun resolveUnchecked(
        route: ProtobufRpcRoute<out GeneratedMessage>,
        message: GeneratedMessage,
    ): RpcTarget {
        @Suppress("UNCHECKED_CAST")
        return (route as ProtobufRpcRoute<GeneratedMessage>).resolve(message)
    }
}

data class ProtobufRpcRoute<M : GeneratedMessage>(
    val messageClass: Class<M>,
    val resolve: (M) -> RpcTarget,
)
