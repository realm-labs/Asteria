package io.github.mikai233.asteria.gateway

import io.github.mikai233.asteria.message.RouteTarget
import kotlinx.coroutines.runBlocking
import java.net.SocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GatewayCoreTest {
    @Test
    fun `session registry rejects duplicate sessions`() {
        val registry = LocalGatewaySessionRegistry()
        val session = GatewaySession(GatewaySessionId("s1"), RecordingConnection())

        registry.register(session)

        assertFailsWith<IllegalStateException> {
            registry.register(session)
        }
    }

    @Test
    fun `packet pipeline applies inbound and outbound interceptors in stable order`() {
        val session = GatewaySession(GatewaySessionId("s1"), RecordingConnection())
        val context = GatewaySessionContext(session)
        val pipeline = GatewayPacketPipeline(
            codec = StringPacketCodec,
            interceptors = listOf(SuffixInterceptor("a"), SuffixInterceptor("b")),
        )

        assertEquals("raw-a-b", pipeline.decode(context, GatewayFrame("raw".encodeToByteArray())))
        assertEquals(GatewayFrame("raw-b-a".encodeToByteArray()), pipeline.encode(context, "raw"))
    }

    @Test
    fun `indexed binary packet codec round trips packet`() {
        val client = IndexedBinaryGatewayPacketCodec()
        val server = IndexedBinaryGatewayPacketCodec()
        val packet = BinaryGatewayPacket(1001, "payload".encodeToByteArray())

        val decoded = server.decode(client.encode(packet))

        assertEquals(packet, decoded)
    }

    @Test
    fun `indexed binary packet codec rejects out of order packet`() {
        val client = IndexedBinaryGatewayPacketCodec()
        val server = IndexedBinaryGatewayPacketCodec()

        client.encode(BinaryGatewayPacket(1, byteArrayOf()))
        val second = client.encode(BinaryGatewayPacket(2, byteArrayOf()))

        assertFailsWith<IllegalArgumentException> {
            server.decode(second)
        }
    }

    @Test
    fun `inbound pipeline resolves and forwards route`() = runBlocking {
        val session = GatewaySession(GatewaySessionId("s1"), RecordingConnection())
        val context = GatewaySessionContext(session)
        var forwarded: Pair<GatewayRoute, String>? = null
        val inbound = GatewayInboundPipeline(
            packets = GatewayPacketPipeline(StringPacketCodec),
            dispatcher = GatewayMessageDispatcher(
                routeResolver = GatewayRouteResolver { _, packet ->
                    GatewayRoute(RouteTarget.GatewayLocal, entityId = packet)
                },
                forwarder = GatewayForwarder { _, route, packet ->
                    forwarded = route to packet
                },
            ),
        )

        val route = inbound.receive(context, GatewayFrame("player-1".encodeToByteArray()))

        assertEquals(GatewayRoute(RouteTarget.GatewayLocal, "player-1"), route)
        assertEquals(route to "player-1", forwarded)
    }

    @Test
    fun `transport handler registers receives and unregisters sessions`() = runBlocking {
        val registry = LocalGatewaySessionRegistry()
        val connection = RecordingConnection()
        var forwarded: String? = null
        val handler = GatewayPipelineTransportHandler(
            sessionFactory = { GatewaySession(GatewaySessionId("s1"), it) },
            sessions = registry,
            inbound = GatewayInboundPipeline(
                packets = GatewayPacketPipeline(StringPacketCodec),
                dispatcher = GatewayMessageDispatcher(
                    routeResolver = GatewayRouteResolver { _, _ -> GatewayRoute(RouteTarget.GatewayLocal) },
                    forwarder = GatewayForwarder { _, _, packet -> forwarded = packet },
                ),
            ),
        )

        val session = handler.connected(connection)
        handler.received(session, GatewayFrame("hello".encodeToByteArray()))
        handler.disconnected(session)

        assertEquals("hello", forwarded)
        assertEquals(null, registry.get(GatewaySessionId("s1")))
    }
}

private object StringPacketCodec : GatewayPacketCodec<String> {
    override fun decode(frame: GatewayFrame): String = frame.bytes.decodeToString()

    override fun encode(packet: String): GatewayFrame = GatewayFrame(packet.encodeToByteArray())
}

private class SuffixInterceptor(
    private val suffix: String,
) : GatewayPacketInterceptor<String> {
    override fun inbound(context: GatewaySessionContext, packet: String): String = "$packet-$suffix"

    override fun outbound(context: GatewaySessionContext, packet: String): String = "$packet-$suffix"
}

private class RecordingConnection : GatewayConnection {
    val frames: MutableList<GatewayFrame> = mutableListOf()
    var closed: Boolean = false

    override val id: GatewayConnectionId = GatewayConnectionId("c1")
    override val transport: GatewayTransportKind = GatewayTransportKind.CUSTOM
    override val remoteAddress: SocketAddress? = null

    override fun write(frame: GatewayFrame) {
        frames += frame
    }

    override fun close() {
        closed = true
    }
}
