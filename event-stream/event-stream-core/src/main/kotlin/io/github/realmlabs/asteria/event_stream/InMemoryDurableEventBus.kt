package io.github.realmlabs.asteria.event_stream

import java.util.concurrent.CancellationException

/**
 * Synchronous in-memory durable event bus for tests and local development.
 *
 * This implementation records published events and immediately invokes local subscribers. It does not provide broker
 * durability, replay, cross-process delivery, or external acknowledgments.
 */
class InMemoryDurableEventBus : DurableEventBus {
    private val events: MutableList<DurableEventEnvelope> = mutableListOf()
    private val handlersByStream: MutableMap<EventStreamName, MutableList<DurableEventHandler>> = linkedMapOf()

    override suspend fun publish(event: DurableEventEnvelope) {
        events += event
        notifyHandlers(event)
    }

    override suspend fun subscribe(
        stream: EventStreamName,
        handler: DurableEventHandler,
    ): DurableEventSubscription {
        handlersByStream.getOrPut(stream) { mutableListOf() } += handler
        return DurableEventSubscription {
            val handlers = handlersByStream[stream] ?: return@DurableEventSubscription
            handlers -= handler
            if (handlers.isEmpty()) {
                handlersByStream -= stream
            }
        }
    }

    fun events(): List<DurableEventEnvelope> {
        return events.toList()
    }

    fun events(stream: EventStreamName): List<DurableEventEnvelope> {
        return events.filter { it.stream == stream }
    }

    private suspend fun notifyHandlers(event: DurableEventEnvelope) {
        var failure: Throwable? = null
        for (handler in handlersByStream[event.stream].orEmpty().toList()) {
            try {
                handler.handle(event)
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
}
