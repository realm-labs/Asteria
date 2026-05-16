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
        val received = mutableListOf<DurableEventDelivery>()
        bus.subscribe(stream) { received += it }
        val orderCreated = event("created".encodeToByteArray())

        val result = bus.publish(orderCreated)

        assertEquals(orderCreated.eventId, result.eventId)
        assertEquals("0", result.offset)
        assertEquals(listOf(orderCreated), bus.events())
        assertEquals(listOf(orderCreated), bus.events(stream))
        assertEquals(listOf(orderCreated), received.map { it.event })
        assertEquals(listOf("0"), received.map { it.offset })
        assertEquals(listOf(1), received.map { it.attempt })
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
    fun `in memory bus replays existing records from earliest`() = runBlocking {
        val bus = InMemoryDurableEventBus()
        val stream = EventStreamName("orders")
        val orderCreated = event("created".encodeToByteArray())
        bus.publish(orderCreated)
        val received = mutableListOf<DurableEventEnvelope>()

        bus.subscribe(
            stream = stream,
            options = DurableEventSubscribeOptions(startPosition = DurableEventStartPosition.Earliest),
        ) {
            received += it.event
        }

        assertEquals(listOf(orderCreated), received)
    }

    @Test
    fun `in memory bus includes consumer group and retry metadata`() = runBlocking {
        val bus = InMemoryDurableEventBus()
        val stream = EventStreamName("orders")
        val attempts = mutableListOf<DurableEventDelivery>()
        bus.subscribe(
            stream = stream,
            options = DurableEventSubscribeOptions(
                consumerGroup = EventStreamConsumerGroup("order-workers"),
                failurePolicy = DurableEventFailurePolicy.retry(maxAttempts = 2),
            ),
        ) {
            attempts += it
            if (it.attempt == 1) error("try again")
        }

        bus.publish(event("created".encodeToByteArray()))

        assertEquals(listOf(1, 2), attempts.map { it.attempt })
        assertEquals(listOf(false, true), attempts.map { it.redelivered })
        assertEquals(listOf("order-workers", "order-workers"), attempts.map { it.consumerGroup?.value })
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

        consumer.subscribe(EventStreamName("orders")) { received += it.event }
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
