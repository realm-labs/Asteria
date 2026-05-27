package io.github.realmlabs.asteria.protocol.protobuf

import com.google.protobuf.BoolValue
import com.google.protobuf.Int32Value
import com.google.protobuf.StringValue
import io.github.realmlabs.asteria.core.EntityKind
import io.github.realmlabs.asteria.gateway.*
import kotlinx.coroutines.runBlocking
import java.net.SocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProtobufProtocolRegistryTest {
    @Test
    fun `protocol registry decodes client messages and encodes server messages`() {
        val registry = ProtobufProtocolRegistry(
            listOf(
                ProtoMapping(1001, ProtoDirection.CLIENT_TO_SERVER, StringValue::class, StringValue.parser()),
                ProtoMapping(1002, ProtoDirection.SERVER_TO_CLIENT, Int32Value::class, Int32Value.parser()),
            ),
        )
        val clientMessage = StringValue.of("hello")
        val serverMessage = Int32Value.of(42)

        val clientFrame = ProtoFrame(1001, clientMessage.toByteArray())
        val clientEnvelope = registry.decode(clientFrame)
        val serverFrame = registry.encode(serverMessage)

        assertEquals(1001, clientEnvelope.id)
        assertEquals(clientMessage, clientEnvelope.message)
        assertEquals(1002, serverFrame.id)
        assertEquals(serverMessage, Int32Value.parseFrom(serverFrame.payload))
    }

    @Test
    fun `protocol registry rejects wrong message direction`() {
        val registry = ProtobufProtocolRegistry(
            listOf(
                ProtoMapping(1001, ProtoDirection.CLIENT_TO_SERVER, StringValue::class, StringValue.parser()),
                ProtoMapping(1002, ProtoDirection.SERVER_TO_CLIENT, Int32Value::class, Int32Value.parser()),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            registry.encode(StringValue.of("client-only"))
        }
        assertFailsWith<IllegalArgumentException> {
            registry.decode(ProtoFrame(1002, Int32Value.of(42).toByteArray()))
        }
    }

    @Test
    fun `gateway protocol resolves protobuf envelope route`() = runBlocking {
        val protocol = protobufGatewayProtocol {
            clientMessage(
                id = 1001,
                messageClass = StringValue::class,
                parser = StringValue.parser(),
                target = RouteTarget.Entity(EntityKind("player")),
                idResolver = { it.value },
            )
            serverMessage(1002, Int32Value::class, Int32Value.parser())
        }
        val envelope = protocol.protocolRegistry.decode(ProtoFrame(1001, StringValue.of("p1").toByteArray()))

        val route = protocol.routeResolver.resolve(testContext(), envelope)

        assertEquals(RouteTarget.Entity(EntityKind("player")), route.target)
        assertEquals("p1", route.entityId)
    }

    @Test
    fun `gateway protocol rejects duplicate protobuf route metadata`() {
        assertFailsWith<IllegalStateException> {
            protobufGatewayProtocol {
                clientMessage(1001, StringValue::class, StringValue.parser(), RouteTarget.GatewayLocal)
                clientMessage(1002, StringValue::class, StringValue.parser(), RouteTarget.GatewayLocal)
            }
        }
    }

    @Test
    fun `gateway protocol supports bidirectional routed message`() = runBlocking {
        val protocol = protobufGatewayProtocol {
            bidirectionalMessage(1001, BoolValue::class, BoolValue.parser(), RouteTarget.GatewayLocal)
        }
        val message = BoolValue.of(true)

        val frame = protocol.protocolRegistry.encode(message)
        val envelope = protocol.protocolRegistry.decode(frame)
        val route = protocol.routeResolver.resolve(testContext(), envelope)

        assertEquals(1001, frame.id)
        assertEquals(message, envelope.message)
        assertEquals(RouteTarget.GatewayLocal, route.target)
    }

    @Test
    fun `gateway protocol supports contributors`() = runBlocking {
        val contributor = ProtobufGatewayProtocolContributor { builder ->
            builder.clientMessage(1001, StringValue::class, StringValue.parser(), RouteTarget.GatewayLocal)
        }
        val protocol = protobufGatewayProtocol {
            include(contributor)
        }
        val envelope = protocol.protocolRegistry.decode(ProtoFrame(1001, StringValue.of("ping").toByteArray()))

        val route = protocol.routeResolver.resolve(testContext(), envelope)

        assertEquals(RouteTarget.GatewayLocal, route.target)
    }

    @Test
    fun `gateway route resolver sees dynamic route replacement`() = runBlocking {
        val protocolRegistry = ProtobufProtocolRegistry(
            listOf(
                ProtoMapping(1001, ProtoDirection.CLIENT_TO_SERVER, StringValue::class, StringValue.parser()),
            ),
        )
        val routes = DynamicRouteRegistry(
            listOf(
                ProtocolRoute(StringValue::class, RouteTarget.GatewayLocal),
            ),
        )
        val resolver = ProtobufGatewayRouteResolver(protocolRegistry, routes)
        val envelope = protocolRegistry.decode(ProtoFrame(1001, StringValue.of("p1").toByteArray()))

        val before = resolver.resolve(testContext(), envelope)
        routes.replace(ProtocolRoute(StringValue::class, RouteTarget.Entity(EntityKind("player"))) { it.value })
        val after = resolver.resolve(testContext(), envelope)

        assertEquals(RouteTarget.GatewayLocal, before.target)
        assertEquals(RouteTarget.Entity(EntityKind("player")), after.target)
        assertEquals("p1", after.entityId)
    }
}

private fun testContext(): GatewaySessionContext {
    return GatewaySessionContext(
        GatewaySession(
            id = GatewaySessionId("s1"),
            connection = object : GatewayConnection {
                override val id: GatewayConnectionId = GatewayConnectionId("c1")
                override val transport: GatewayTransportKind = GatewayTransportKind.CUSTOM
                override val remoteAddress: SocketAddress? = null

                override fun write(frame: GatewayFrame) = Unit

                override fun close() = Unit
            },
        ),
    )
}
