package io.github.realmlabs.asteria.gateway

import io.github.realmlabs.asteria.message.RouteTarget
import kotlinx.coroutines.runBlocking
import java.net.SocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

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
    fun `dispatcher resolves and forwards route`(): Unit = runBlocking {
        val session = GatewaySession(GatewaySessionId("s1"), RecordingConnection())
        val context = GatewaySessionContext(session)
        var forwarded: Pair<GatewayRoute, String>? = null
        val dispatcher = GatewayMessageDispatcher<String>(
            routeResolver = { _, packet ->
                GatewayRoute(RouteTarget.GatewayLocal, entityId = packet)
            },
            forwarder = { _, route, packet ->
                forwarded = route to packet
            },
        )

        val route = dispatcher.dispatch(context, "player-1")

        assertEquals(GatewayRoute(RouteTarget.GatewayLocal, "player-1"), route)
        assertEquals(route to "player-1", forwarded)
    }

    @Test
    fun `transport handler registers receives and unregisters sessions`(): Unit = runBlocking {
        val registry = LocalGatewaySessionRegistry()
        val connection = RecordingConnection()
        var forwarded: String? = null
        val lifecycle = RecordingLifecycle()
        val handler = GatewaySessionTransportHandler(
            sessionFactory = { GatewaySession(GatewaySessionId("s1"), it) },
            sessions = registry,
            lifecycle = lifecycle,
            receiver = GatewayFrameReceiver { context, frame ->
                GatewayMessageDispatcher<String>(
                    routeResolver = { _, _ -> GatewayRoute(RouteTarget.GatewayLocal) },
                    forwarder = { _, _, packet -> forwarded = packet },
                ).dispatch(context, frame.bytes.decodeToString())
            },
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
    fun `session responder writes packet to session`(): Unit = runBlocking {
        val registry = LocalGatewaySessionRegistry()
        val connection = RecordingConnection()
        val session = GatewaySession(GatewaySessionId("s1"), connection)
        registry.register(session)
        val responder = SessionGatewayResponder<String>(
            sessions = registry,
            write = { target, packet -> target.write(GatewayFrame("$packet-out".encodeToByteArray())) },
        )

        val sent = responder.respond(GatewaySessionId("s1"), "reply")

        assertEquals(true, sent)
        assertEquals(listOf(GatewayFrame("reply-out".encodeToByteArray())), connection.frames)
    }

    @Test
    fun `session responder returns false for missing session`(): Unit = runBlocking {
        val responder = SessionGatewayResponder<String>(
            sessions = LocalGatewaySessionRegistry(),
            write = { target, packet -> target.write(GatewayFrame(packet.encodeToByteArray())) },
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
        assertEquals(true, session.lastWriteAt >= firstWriteAt)
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
    fun `session controller closes and unregisters session with lifecycle`(): Unit = runBlocking {
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
        val session = GatewaySession(GatewaySessionId("s1"), RecordingConnection(), createdAt = now - 60.seconds)
        registry.register(session)
        session.markRead(now - 20.seconds)

        val detector = GatewayIdleDetector(
            registry,
            GatewayIdlePolicy(
                readIdle = 10.seconds,
                writeIdle = 30.seconds,
                allIdle = 10.seconds,
            ),
        )

        val detected = detector.detect(now)

        assertEquals(
            listOf(GatewayIdleKind.READ, GatewayIdleKind.WRITE, GatewayIdleKind.ALL),
            detected.map { it.kind },
        )
    }
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
