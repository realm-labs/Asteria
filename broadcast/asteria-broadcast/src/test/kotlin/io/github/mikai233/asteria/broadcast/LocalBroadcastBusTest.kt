package io.github.mikai233.asteria.broadcast

import io.github.mikai233.asteria.core.gameApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalBroadcastBusTest {
    @Test
    fun `publish delivers to subscribers on same topic`() {
        val bus = LocalBroadcastBus()
        val topic = BroadcastTopic("world:1")
        val received = mutableListOf<Any>()

        bus.subscribe(topic) { received += it.payload }
        bus.publish(topic, "online")

        assertEquals(listOf<Any>("online"), received)
    }

    @Test
    fun `publish does not deliver to other topics`() {
        val bus = LocalBroadcastBus()
        val received = mutableListOf<Any>()

        bus.subscribe(BroadcastTopic("world:1")) { received += it.payload }
        bus.publish(BroadcastTopic("world:2"), "online")

        assertTrue(received.isEmpty())
    }

    @Test
    fun `subscription close removes subscriber`() {
        val bus = LocalBroadcastBus()
        val topic = BroadcastTopic("global")
        val received = mutableListOf<Any>()
        val subscription = bus.subscribe(topic) { received += it.payload }

        subscription.close()
        bus.publish(topic, "notice")

        assertTrue(received.isEmpty())
        assertEquals(0, bus.subscriberCount(topic))
        assertTrue(bus.topics().isEmpty())
    }

    @Test
    fun `expired envelope is dropped`() {
        val bus = LocalBroadcastBus()
        val received = mutableListOf<Any>()
        val topic = BroadcastTopic("global")

        bus.subscribe(topic) { received += it.payload }
        bus.publish(
            BroadcastEnvelope(
                topic = topic,
                payload = "notice",
                createdAtMillis = 100,
                ttlMillis = 10,
            ),
        )

        assertTrue(received.isEmpty())
    }

    @Test
    fun `subscriber can unsubscribe during publish`() {
        val bus = LocalBroadcastBus()
        val topic = BroadcastTopic("global")
        val received = mutableListOf<String>()
        lateinit var firstSubscription: BroadcastSubscription
        firstSubscription = bus.subscribe(topic) {
            received += "first:${it.payload}"
            firstSubscription.close()
        }
        bus.subscribe(topic) { received += "second:${it.payload}" }

        bus.publish(topic, "one")
        bus.publish(topic, "two")

        assertEquals(listOf("first:one", "second:one", "second:two"), received)
    }

    @Test
    fun `local broadcast module registers broadcast bus service`() = runBlocking {
        val application = gameApplication {
            install(LocalBroadcastModule())
        }

        application.launch()
        val bus = application.services.get(BroadcastBus::class)
        val topic = BroadcastTopic("global")
        val received = mutableListOf<Any>()

        bus.subscribe(topic) { received += it.payload }
        bus.publish(topic, "notice")

        assertEquals(listOf<Any>("notice"), received)
        application.stop()
    }
}
