package io.github.realmlabs.asteria.gateway

import io.github.realmlabs.asteria.message.RouteTarget
import io.github.realmlabs.asteria.observability.Counter
import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.Timer
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
    fun `dispatcher records gateway route metrics`(): Unit = runBlocking {
        val metrics = RecordingMetrics()
        val session = GatewaySession(GatewaySessionId("s1"), RecordingConnection())
        val context = GatewaySessionContext(session)
        val dispatcher = GatewayMessageDispatcher<String>(
            routeResolver = { _, packet ->
                GatewayRoute(RouteTarget.GatewayLocal, entityId = packet)
            },
            forwarder = { _, _, _ -> },
            metrics = metrics,
        )

        dispatcher.dispatch(context, "player-1")

        assertEquals(1, metrics.counter("asteria.gateway.dispatch.total", mapOf("transport" to "CUSTOM")))
        assertEquals(
            1,
            metrics.counter(
                "asteria.gateway.dispatch.forwarded.total",
                mapOf("transport" to "CUSTOM", "target" to "gateway_local"),
            ),
        )
        assertEquals(1, metrics.timerCount("asteria.gateway.dispatch.duration", mapOf("transport" to "CUSTOM")))
    }

    @Test
    fun `dispatcher records failed gateway route metrics`(): Unit = runBlocking {
        val metrics = RecordingMetrics()
        val session = GatewaySession(GatewaySessionId("s1"), RecordingConnection())
        val context = GatewaySessionContext(session)
        val dispatcher = GatewayMessageDispatcher<String>(
            routeResolver = { _, _ ->
                GatewayRoute(RouteTarget.Entity(io.github.realmlabs.asteria.core.EntityKind("player")), entityId = "p1")
            },
            forwarder = { _, _, _ -> error("forward failed") },
            metrics = metrics,
        )

        assertFailsWith<IllegalStateException> {
            dispatcher.dispatch(context, "player-1")
        }

        assertEquals(1, metrics.counter("asteria.gateway.dispatch.total", mapOf("transport" to "CUSTOM")))
        assertEquals(
            1,
            metrics.counter(
                "asteria.gateway.dispatch.failed.total",
                mapOf("transport" to "CUSTOM", "target" to "entity"),
            ),
        )
        assertEquals(1, metrics.timerCount("asteria.gateway.dispatch.duration", mapOf("transport" to "CUSTOM")))
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
    fun `composite lifecycle continues after hook failure`(): Unit = runBlocking {
        val events = mutableListOf<String>()
        val session = GatewaySession(GatewaySessionId("s1"), RecordingConnection())
        val context = GatewaySessionContext(session)
        val lifecycle = CompositeGatewaySessionLifecycle(
            listOf(
                object : GatewaySessionLifecycle {
                    override suspend fun beforeClose(context: GatewaySessionContext, reason: GatewayCloseReason) {
                        events += "first"
                        error("lifecycle failed")
                    }
                },
                object : GatewaySessionLifecycle {
                    override suspend fun beforeClose(context: GatewaySessionContext, reason: GatewayCloseReason) {
                        events += "second"
                    }
                },
            ),
        )

        lifecycle.beforeClose(context, GatewayCloseReason.Application)

        assertEquals(listOf("first", "second"), events)
    }

    @Test
    fun `transport disconnect closes session when lifecycle fails`(): Unit = runBlocking {
        val registry = LocalGatewaySessionRegistry()
        val events = mutableListOf<String>()
        val lifecycle = object : GatewaySessionLifecycle {
            override suspend fun beforeClose(context: GatewaySessionContext, reason: GatewayCloseReason) {
                events += "beforeClose"
                error("lifecycle failed")
            }

            override suspend fun disconnected(context: GatewaySessionContext, reason: GatewayCloseReason) {
                events += "disconnected"
            }

            override suspend fun afterClose(context: GatewaySessionContext, reason: GatewayCloseReason) {
                events += "afterClose"
            }
        }
        val handler = GatewaySessionTransportHandler(
            sessionFactory = { GatewaySession(GatewaySessionId("s1"), it) },
            sessions = registry,
            lifecycle = lifecycle,
            receiver = GatewayFrameReceiver { _, _ -> },
        )

        val session = handler.connected(RecordingConnection())
        handler.disconnected(session)

        assertEquals(null, registry.get(GatewaySessionId("s1")))
        assertEquals(GatewaySessionState.CLOSED, session.state)
        assertEquals(listOf("beforeClose", "disconnected", "afterClose"), events)
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

private class RecordingMetrics : Metrics {
    private val counters: MutableMap<MetricKey, Long> = linkedMapOf()
    private val timers: MutableMap<MetricKey, MutableList<Long>> = linkedMapOf()

    override fun counter(name: String, tags: MetricTags): Counter {
        val key = MetricKey(name, tags.asMap())
        return object : Counter {
            override fun increment(amount: Long) {
                counters[key] = counter(name, tags.asMap()) + amount
            }
        }
    }

    override fun timer(name: String, tags: MetricTags): Timer {
        val key = MetricKey(name, tags.asMap())
        return object : Timer {
            override suspend fun <T> record(block: suspend () -> T): T {
                return block()
            }

            override fun record(durationMillis: Long) {
                timers.getOrPut(key) { mutableListOf() } += durationMillis
            }
        }
    }

    override fun gauge(name: String, tags: MetricTags, value: () -> Double) {
    }

    fun counter(name: String, tags: Map<String, String>): Long {
        return counters[MetricKey(name, tags)] ?: 0
    }

    fun timerCount(name: String, tags: Map<String, String>): Int {
        return timers[MetricKey(name, tags)]?.size ?: 0
    }
}

private data class MetricKey(
    val name: String,
    val tags: Map<String, String>,
)
