package io.github.realmlabs.asteria.ephemeral.broadcast

import io.github.realmlabs.asteria.core.gameApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalEphemeralBroadcastBusTest {
    @Test
    fun `publish delivers to subscribers on same topic`() {
        val bus = LocalEphemeralBroadcastBus()
        val topic = EphemeralBroadcastTopic("world:1")
        val received = mutableListOf<Any>()

        bus.subscribe(topic) { received += it.payload }
        bus.publish(topic, "online")

        assertEquals(listOf<Any>("online"), received)
    }

    @Test
    fun `publish does not deliver to other topics`() {
        val bus = LocalEphemeralBroadcastBus()
        val received = mutableListOf<Any>()

        bus.subscribe(EphemeralBroadcastTopic("world:1")) { received += it.payload }
        bus.publish(EphemeralBroadcastTopic("world:2"), "online")

        assertTrue(received.isEmpty())
    }

    @Test
    fun `subscription close removes subscriber`() {
        val bus = LocalEphemeralBroadcastBus()
        val topic = EphemeralBroadcastTopic("global")
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
        val bus = LocalEphemeralBroadcastBus()
        val received = mutableListOf<Any>()
        val topic = EphemeralBroadcastTopic("global")

        bus.subscribe(topic) { received += it.payload }
        bus.publish(
            EphemeralBroadcastEnvelope(
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
        val bus = LocalEphemeralBroadcastBus()
        val topic = EphemeralBroadcastTopic("global")
        val received = mutableListOf<String>()
        lateinit var firstSubscription: EphemeralBroadcastSubscription
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
    fun `subscriber error does not block later subscribers`() {
        val bus = LocalEphemeralBroadcastBus()
        val topic = EphemeralBroadcastTopic("global")
        val received = mutableListOf<String>()

        bus.subscribe(topic) { error("subscriber failed") }
        bus.subscribe(topic) { received += "second:${it.payload}" }

        bus.publish(topic, "notice")

        assertEquals(listOf("second:notice"), received)
    }

    @Test
    fun `local ephemeral broadcast module registers broadcast bus service`() = runBlocking {
        val application = gameApplication {
            install(LocalEphemeralBroadcastModule())
        }

        application.launch()
        val bus = application.services.get(EphemeralBroadcastBus::class)
        val topic = EphemeralBroadcastTopic("global")
        val received = mutableListOf<Any>()

        bus.subscribe(topic) { received += it.payload }
        bus.publish(topic, "notice")

        assertEquals(listOf<Any>("notice"), received)
        application.stop()
    }
}
