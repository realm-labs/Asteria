package io.github.realmlabs.asteria.event_stream

import io.github.realmlabs.asteria.core.gameApplication
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DurableEventTest {
    @Test
    fun `durable event envelope uses payload content equality`() {
        val first = event("created".encodeToByteArray())
        val second = event("created".encodeToByteArray())

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `in memory bus records and dispatches events`() = runBlocking {
        val bus = InMemoryDurableEventBus()
        val stream = EventStreamName("orders")
        val received = mutableListOf<DurableEventEnvelope>()
        bus.subscribe(stream) { received += it }
        val orderCreated = event("created".encodeToByteArray())

        bus.publish(orderCreated)

        assertEquals(listOf(orderCreated), bus.events())
        assertEquals(listOf(orderCreated), bus.events(stream))
        assertEquals(listOf(orderCreated), received)
    }

    @Test
    fun `in memory bus tries later handlers before rethrowing`() = runBlocking {
        val bus = InMemoryDurableEventBus()
        val stream = EventStreamName("orders")
        val received = mutableListOf<String>()
        bus.subscribe(stream) {
            received += "first"
            error("handler failed")
        }
        bus.subscribe(stream) {
            received += "second"
        }

        val error = assertFailsWith<IllegalStateException> {
            bus.publish(event("created".encodeToByteArray()))
        }

        assertEquals("handler failed", error.message)
        assertEquals(listOf("first", "second"), received)
    }

    @Test
    fun `in memory durable event module registers durable event services`() = runBlocking {
        val application = gameApplication {
            install(InMemoryDurableEventModule())
        }

        application.launch()
        val publisher = application.services.get(DurableEventPublisher::class)
        val consumer = application.services.get(DurableEventConsumer::class)
        val received = mutableListOf<DurableEventEnvelope>()

        consumer.subscribe(EventStreamName("orders")) { received += it }
        val orderCreated = event("created".encodeToByteArray())
        publisher.publish(orderCreated)

        assertEquals(listOf(orderCreated), received)
        application.stop()
    }

    private fun event(payload: ByteArray): DurableEventEnvelope {
        return DurableEventEnvelope(
            stream = EventStreamName("orders"),
            type = DurableEventType("order.created"),
            payload = payload,
            key = "order-1",
            headers = mapOf("content-type" to "application/json"),
            eventId = "event-1",
            occurredAt = Instant.parse("2026-05-16T00:00:00Z"),
            correlationId = "request-1",
        )
    }
}
