package io.github.mikai233.asteria.protocol.protobuf

import com.google.protobuf.GeneratedMessage
import com.google.protobuf.Parser
import io.github.mikai233.asteria.gateway.GatewayRoute
import io.github.mikai233.asteria.gateway.GatewayRouteResolver
import io.github.mikai233.asteria.gateway.GatewaySessionContext
import io.github.mikai233.asteria.message.ProtocolRouteRegistry
import io.github.mikai233.asteria.message.RouteRegistryBuilder
import io.github.mikai233.asteria.message.RouteTarget
import kotlin.reflect.KClass

/**
 * Gateway-facing protobuf metadata.
 *
 * It keeps protocol id metadata and routing metadata together so startup fails when a client packet is missing a route or
 * when an inbound route points at a server-only protobuf message.
 */
class ProtobufGatewayProtocol(
    val protocolRegistry: ProtobufProtocolRegistry,
    routeRegistry: ProtocolRouteRegistry,
) {
    val routeResolver: GatewayRouteResolver<ClientProtoEnvelope> =
        ProtobufGatewayRouteResolver(protocolRegistry, routeRegistry)
}

fun interface ProtobufGatewayProtocolContributor {
    fun contribute(builder: ProtobufGatewayProtocolBuilder)
}

/**
 * Resolves decoded client protobuf envelopes into gateway routes.
 */
class ProtobufGatewayRouteResolver(
    private val protocolRegistry: ProtobufProtocolRegistry,
    private val routeRegistry: ProtocolRouteRegistry,
) : GatewayRouteResolver<ClientProtoEnvelope> {
    override suspend fun resolve(context: GatewaySessionContext, packet: ClientProtoEnvelope): GatewayRoute {
        val direction = protocolRegistry.directionFor(packet.id)
        require(direction.allowsClientToServer) {
            "protobuf message id ${packet.id} is not allowed in client-to-server direction"
        }
        val route = requireNotNull(routeRegistry.routeFor(packet.message::class)) {
            "gateway route for protobuf message ${packet.message::class.qualifiedName} not found"
        }
        return GatewayRoute(
            target = route.target,
            entityId = route.resolveId(packet.message),
        )
    }
}

class ProtobufGatewayProtocolBuilder {
    private val protocolMappings: MutableList<ProtoMapping<out GeneratedMessage>> = mutableListOf()
    private val routes = RouteRegistryBuilder()

    fun include(contributor: ProtobufGatewayProtocolContributor) {
        contributor.contribute(this)
    }

    inline fun <reified M : GeneratedMessage> clientMessage(
        id: Int,
        parser: Parser<out M>,
        target: RouteTarget,
        noinline idResolver: ((M) -> Any?)? = null,
    ) {
        clientMessage(id, M::class, parser, target, idResolver)
    }

    fun <M : GeneratedMessage> clientMessage(
        id: Int,
        messageClass: KClass<M>,
        parser: Parser<out M>,
        target: RouteTarget,
        idResolver: ((M) -> Any?)? = null,
    ) {
        protocolMappings += ProtoMapping(id, ProtoDirection.CLIENT_TO_SERVER, messageClass, parser)
        routes.route(messageClass, target, idResolver)
    }

    inline fun <reified M : GeneratedMessage> serverMessage(
        id: Int,
        parser: Parser<out M>,
    ) {
        serverMessage(id, M::class, parser)
    }

    fun <M : GeneratedMessage> serverMessage(
        id: Int,
        messageClass: KClass<M>,
        parser: Parser<out M>,
    ) {
        protocolMappings += ProtoMapping(id, ProtoDirection.SERVER_TO_CLIENT, messageClass, parser)
    }

    inline fun <reified M : GeneratedMessage> bidirectionalMessage(
        id: Int,
        parser: Parser<out M>,
        target: RouteTarget,
        noinline idResolver: ((M) -> Any?)? = null,
    ) {
        bidirectionalMessage(id, M::class, parser, target, idResolver)
    }

    fun <M : GeneratedMessage> bidirectionalMessage(
        id: Int,
        messageClass: KClass<M>,
        parser: Parser<out M>,
        target: RouteTarget,
        idResolver: ((M) -> Any?)? = null,
    ) {
        protocolMappings += ProtoMapping(id, ProtoDirection.BIDIRECTIONAL, messageClass, parser)
        routes.route(messageClass, target, idResolver)
    }

    fun build(): ProtobufGatewayProtocol {
        val protocolRegistry = ProtobufProtocolRegistry(protocolMappings)
        val routeRegistry = routes.build()
        validateInboundRoutes(protocolRegistry, routeRegistry)
        return ProtobufGatewayProtocol(protocolRegistry, routeRegistry)
    }

    private fun validateInboundRoutes(
        protocolRegistry: ProtobufProtocolRegistry,
        routeRegistry: ProtocolRouteRegistry,
    ) {
        routeRegistry.all().forEach { route ->
            val messageClass = route.messageType.java.asSubclass(GeneratedMessage::class.java)
            val direction = protocolRegistry.directionFor(messageClass)
            check(direction.allowsClientToServer) {
                "gateway route for ${route.messageType.qualifiedName} points at a $direction protobuf message"
            }
        }
    }
}

fun protobufGatewayProtocol(
    configure: ProtobufGatewayProtocolBuilder.() -> Unit,
): ProtobufGatewayProtocol {
    return ProtobufGatewayProtocolBuilder().apply(configure).build()
}
