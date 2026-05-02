package io.github.mikai233.asteria.gateway

import io.github.mikai233.asteria.message.RouteTarget
import kotlinx.coroutines.runBlocking
import java.net.SocketAddress
import java.time.Duration
import java.time.Instant
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
        val lifecycle = RecordingLifecycle()
        val handler = GatewayPipelineTransportHandler(
            sessionFactory = { GatewaySession(GatewaySessionId("s1"), it) },
            sessions = registry,
            lifecycle = lifecycle,
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
        assertEquals(
            listOf(
                "connected:s1",
                "beforeClose:s1:transport_inactive",
                "disconnected:s1:transport_inactive",
                "afterClose:s1:transport_inactive",
            ),
            lifecycle.events,
        )
        assertEquals(GatewaySessionState.CLOSED, session.state)
        assertEquals(GatewayCloseReason.TransportInactive, session.closeReason)
    }

    @Test
    fun `session responder encodes and writes packet to session`() = runBlocking {
        val registry = LocalGatewaySessionRegistry()
        val connection = RecordingConnection()
        val session = GatewaySession(GatewaySessionId("s1"), connection)
        registry.register(session)
        val responder = SessionGatewayResponder(
            sessions = registry,
            packets = GatewayPacketPipeline(
                codec = StringPacketCodec,
                interceptors = listOf(SuffixInterceptor("out")),
            ),
        )

        val sent = responder.respond(GatewaySessionId("s1"), "reply")

        assertEquals(true, sent)
        assertEquals(listOf(GatewayFrame("reply-out".encodeToByteArray())), connection.frames)
    }

    @Test
    fun `session responder returns false for missing session`() = runBlocking {
        val responder = SessionGatewayResponder(
            sessions = LocalGatewaySessionRegistry(),
            packets = GatewayPacketPipeline(StringPacketCodec),
        )

        val sent = responder.respond(GatewaySessionId("missing"), "reply")

        assertEquals(false, sent)
    }

    @Test
    fun `session write and close track state`() {
        val connection = RecordingConnection()
        val session = GatewaySession(GatewaySessionId("s1"), connection)
        val firstWriteAt = session.lastWriteAt

        session.write(GatewayFrame("hello".encodeToByteArray()))
        val closed = session.close(GatewayCloseReason("test"))
        val closedAgain = session.close(GatewayCloseReason("second"))

        assertEquals(listOf(GatewayFrame("hello".encodeToByteArray())), connection.frames)
        assertEquals(true, !session.lastWriteAt.isBefore(firstWriteAt))
        assertEquals(true, closed)
        assertEquals(false, closedAgain)
        assertEquals(true, connection.closed)
        assertEquals(GatewaySessionState.CLOSED, session.state)
        assertEquals("test", session.closeReason?.code)
        assertFailsWith<IllegalStateException> {
            session.write(GatewayFrame("late".encodeToByteArray()))
        }
    }

    @Test
    fun `session controller closes and unregisters session with lifecycle`() = runBlocking {
        val registry = LocalGatewaySessionRegistry()
        val lifecycle = RecordingLifecycle()
        val session = GatewaySession(GatewaySessionId("s1"), RecordingConnection())
        registry.register(session)
        val controller = GatewaySessionController(registry, lifecycle)

        val closed = controller.close(GatewaySessionId("s1"), GatewayCloseReason.IdleTimeout)

        assertEquals(true, closed)
        assertEquals(null, registry.get(GatewaySessionId("s1")))
        assertEquals(GatewaySessionState.CLOSED, session.state)
        assertEquals(GatewayCloseReason.IdleTimeout, session.closeReason)
        assertEquals(
            listOf(
                "beforeClose:s1:idle_timeout",
                "disconnected:s1:idle_timeout",
                "afterClose:s1:idle_timeout",
            ),
            lifecycle.events,
        )
    }

    @Test
    fun `idle detector reports idle sessions without applying policy`() {
        val registry = LocalGatewaySessionRegistry()
        val now = Instant.parse("2026-05-02T00:00:00Z")
        val session = GatewaySession(GatewaySessionId("s1"), RecordingConnection(), createdAt = now.minusSeconds(60))
        registry.register(session)
        session.markRead(now.minusSeconds(20))

        val detector = GatewayIdleDetector(
            registry,
            GatewayIdlePolicy(
                readIdle = Duration.ofSeconds(10),
                writeIdle = Duration.ofSeconds(30),
                allIdle = Duration.ofSeconds(10),
            ),
        )

        val detected = detector.detect(now)

        assertEquals(
            listOf(GatewayIdleKind.READ, GatewayIdleKind.WRITE, GatewayIdleKind.ALL),
            detected.map { it.kind },
        )
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

private class RecordingLifecycle : GatewaySessionLifecycle {
    val events: MutableList<String> = mutableListOf()

    override suspend fun connected(context: GatewaySessionContext) {
        events += "connected:${context.session.id.value}"
    }

    override suspend fun beforeClose(context: GatewaySessionContext, reason: GatewayCloseReason) {
        events += "beforeClose:${context.session.id.value}:${reason.code}"
    }

    override suspend fun disconnected(context: GatewaySessionContext, reason: GatewayCloseReason) {
        events += "disconnected:${context.session.id.value}:${reason.code}"
    }

    override suspend fun afterClose(context: GatewaySessionContext, reason: GatewayCloseReason) {
        events += "afterClose:${context.session.id.value}:${reason.code}"
    }
}
