package io.github.realmlabs.asteria.event.stream

import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class DurableEventOutboxTest {
    @Test
    fun `outbox pump publishes due records`() = runBlocking {
        val now = Instant.parse("2026-05-16T00:00:00Z")
        val store = InMemoryDurableEventOutboxStore()
        val publisher = InMemoryDurableEventBus()
        val event = event("created".encodeToByteArray())
        val record = store.append(event, id = DurableEventOutboxRecordId("outbox-1"), now = now)
        val pump = DurableEventOutboxPump(store, publisher)

        val result = pump.drainOnce(now)

        assertEquals(DurableEventOutboxDrainResult(claimed = 1, published = 1, failed = 0), result)
        assertEquals(listOf(event), publisher.events())
        assertEquals(DurableEventOutboxStatus.Published, store.record(record.id).status)
        assertEquals("0", store.record(record.id).publishResult?.offset)
    }

    @Test
    fun `outbox pump marks failed records for retry`() = runBlocking {
        val now = Instant.parse("2026-05-16T00:00:00Z")
        val store = InMemoryDurableEventOutboxStore()
        val record = store.append(event("created".encodeToByteArray()), id = DurableEventOutboxRecordId("outbox-1"), now = now)
        val publisher = object : DurableEventPublisher {
            override suspend fun publish(event: DurableEventEnvelope): DurableEventPublishResult {
                error("broker unavailable")
            }
        }
        val pump = DurableEventOutboxPump(
            store = store,
            publisher = publisher,
            options = DurableEventOutboxPumpOptions(
                retryDelay = { _, _ -> Duration.ofSeconds(10) },
            ),
        )

        val result = pump.drainOnce(now)

        assertEquals(DurableEventOutboxDrainResult(claimed = 1, published = 0, failed = 1), result)
        val failed = store.record(record.id)
        assertEquals(DurableEventOutboxStatus.Failed, failed.status)
        assertEquals(1, failed.attempts)
        assertEquals("broker unavailable", failed.lastError)
    }

    @Test
    fun `outbox store does not claim records before next attempt`() = runBlocking {
        val now = Instant.parse("2026-05-16T00:00:00Z")
        val store = InMemoryDurableEventOutboxStore()
        val record = store.append(event("created".encodeToByteArray()), id = DurableEventOutboxRecordId("outbox-1"), now = now)
        store.markFailed(record.id, IllegalStateException("failed"), nextAttemptAt = now.plusSeconds(30), now = now)

        val claimed = store.claimDue(limit = 10, now = now.plusSeconds(29))

        assertEquals(emptyList(), claimed)
    }

    private fun event(payload: ByteArray): DurableEventEnvelope {
        return DurableEventEnvelope(
            stream = EventStreamName("orders"),
            type = DurableEventType("order.created"),
            payload = payload,
            key = "order-1",
            eventId = "event-1",
            occurredAt = Instant.parse("2026-05-16T00:00:00Z"),
        )
    }
}
