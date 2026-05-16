package io.github.realmlabs.asteria.event_stream

import java.util.concurrent.CancellationException

/**
 * Synchronous in-memory durable event bus for tests and local development.
 *
 * This implementation records published events and immediately invokes local subscribers. It does not provide broker
 * durability, replay, cross-process delivery, or external acknowledgments.
 */
class InMemoryDurableEventBus : DurableEventBus {
    private val records: MutableList<InMemoryDurableEventRecord> = mutableListOf()
    private val handlersByStream: MutableMap<EventStreamName, MutableList<InMemoryDurableEventHandler>> = linkedMapOf()

    override suspend fun publish(event: DurableEventEnvelope): DurableEventPublishResult {
        val result = DurableEventPublishResult(
            stream = event.stream,
            eventId = event.eventId,
            offset = records.size.toString(),
        )
        val record = InMemoryDurableEventRecord(event, result)
        records += record
        notifyHandlers(record)
        return result
    }

    override suspend fun subscribe(
        stream: EventStreamName,
        options: DurableEventSubscribeOptions,
        handler: DurableEventHandler,
    ): DurableEventSubscription {
        val registeredHandler = InMemoryDurableEventHandler(options, handler)
        handlersByStream.getOrPut(stream) { mutableListOf() } += registeredHandler
        try {
            replayExistingRecords(stream, registeredHandler)
        } catch (error: Throwable) {
            unsubscribe(stream, registeredHandler)
            throw error
        }
        return DurableEventSubscription {
            unsubscribe(stream, registeredHandler)
        }
    }

    fun events(): List<DurableEventEnvelope> {
        return records.map { it.event }
    }

    fun events(stream: EventStreamName): List<DurableEventEnvelope> {
        return records.map { it.event }.filter { it.stream == stream }
    }

    fun records(): List<DurableEventPublishResult> {
        return records.map { it.result }
    }

    private fun unsubscribe(
        stream: EventStreamName,
        handler: InMemoryDurableEventHandler,
    ) {
        val handlers = handlersByStream[stream] ?: return
        handlers -= handler
        if (handlers.isEmpty()) {
            handlersByStream -= stream
        }
    }

    private suspend fun replayExistingRecords(
        stream: EventStreamName,
        handler: InMemoryDurableEventHandler,
    ) {
        records.asSequence()
            .filter { it.event.stream == stream }
            .filter { handler.options.startPosition.matches(it) }
            .forEach { record ->
                deliver(record, handler)
            }
    }

    private suspend fun notifyHandlers(record: InMemoryDurableEventRecord) {
        var failure: Throwable? = null
        for (handler in handlersByStream[record.event.stream].orEmpty().toList()) {
            try {
                deliver(record, handler)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run {
                    failure = error
                }
            }
        }
        failure?.let { throw it }
    }

    private suspend fun deliver(
        record: InMemoryDurableEventRecord,
        handler: InMemoryDurableEventHandler,
    ) {
        val maxAttempts = handler.options.failurePolicy.maxAttempts ?: 1
        var attempt = 1
        var failure: Throwable? = null
        while (attempt <= maxAttempts) {
            try {
                handler.handler.handle(
                    DurableEventDelivery(
                        event = record.event,
                        consumerGroup = handler.options.consumerGroup,
                        offset = record.result.offset,
                        attempt = attempt,
                        redelivered = attempt > 1,
                    ),
                )
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failure = error
                attempt += 1
            }
        }
        throw failure ?: error("durable event delivery failed")
    }

    private fun DurableEventStartPosition.matches(record: InMemoryDurableEventRecord): Boolean {
        return when (this) {
            DurableEventStartPosition.Earliest -> true
            DurableEventStartPosition.Latest -> false
            is DurableEventStartPosition.FromOffset -> {
                val recordOffset = record.result.offset?.toLongOrNull()
                val requestedOffset = offset.toLongOrNull()
                if (recordOffset != null && requestedOffset != null) {
                    recordOffset >= requestedOffset
                } else {
                    record.result.offset == offset
                }
            }

            is DurableEventStartPosition.FromTimestamp -> !record.event.occurredAt.isBefore(timestamp)
        }
    }
}

private data class InMemoryDurableEventRecord(
    val event: DurableEventEnvelope,
    val result: DurableEventPublishResult,
)

private data class InMemoryDurableEventHandler(
    val options: DurableEventSubscribeOptions,
    val handler: DurableEventHandler,
)
