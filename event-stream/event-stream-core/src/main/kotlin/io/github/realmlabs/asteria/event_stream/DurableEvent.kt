package io.github.realmlabs.asteria.event_stream

import java.time.Instant
import java.util.UUID

/**
 * Durable event stream name.
 *
 * A stream identifies the durable channel used by a backend, such as a topic, exchange, stream, or subject.
 */
@JvmInline
value class EventStreamName(val value: String) {
    init {
        require(value.isNotBlank()) { "event stream name must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Stable event type name used for schema lookup and dispatch.
 */
@JvmInline
value class DurableEventType(val value: String) {
    init {
        require(value.isNotBlank()) { "durable event type must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Event envelope for business facts that must be persisted by a durable event stream.
 *
 * This abstraction is intentionally separate from ephemeral broadcast. Implementations should treat a successful publish
 * as acceptance by a durable broker or store, not as immediate delivery to online subscribers.
 */
class DurableEventEnvelope(
    val stream: EventStreamName,
    val type: DurableEventType,
    val payload: ByteArray,
    val key: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val eventId: String = UUID.randomUUID().toString(),
    val occurredAt: Instant = Instant.now(),
    val correlationId: String? = null,
    val causationId: String? = null,
) {
    init {
        require(key == null || key.isNotBlank()) { "durable event key must not be blank" }
        require(eventId.isNotBlank()) { "durable event id must not be blank" }
        require(correlationId == null || correlationId.isNotBlank()) { "durable event correlation id must not be blank" }
        require(causationId == null || causationId.isNotBlank()) { "durable event causation id must not be blank" }
        headers.forEach { (name, _) ->
            require(name.isNotBlank()) { "durable event header name must not be blank" }
        }
    }

    fun copy(
        stream: EventStreamName = this.stream,
        type: DurableEventType = this.type,
        payload: ByteArray = this.payload,
        key: String? = this.key,
        headers: Map<String, String> = this.headers,
        eventId: String = this.eventId,
        occurredAt: Instant = this.occurredAt,
        correlationId: String? = this.correlationId,
        causationId: String? = this.causationId,
    ): DurableEventEnvelope {
        return DurableEventEnvelope(
            stream = stream,
            type = type,
            payload = payload,
            key = key,
            headers = headers,
            eventId = eventId,
            occurredAt = occurredAt,
            correlationId = correlationId,
            causationId = causationId,
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DurableEventEnvelope) return false

        return stream == other.stream &&
                type == other.type &&
                payload.contentEquals(other.payload) &&
                key == other.key &&
                headers == other.headers &&
                eventId == other.eventId &&
                occurredAt == other.occurredAt &&
                correlationId == other.correlationId &&
                causationId == other.causationId
    }

    override fun hashCode(): Int {
        var result = stream.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + (key?.hashCode() ?: 0)
        result = 31 * result + headers.hashCode()
        result = 31 * result + eventId.hashCode()
        result = 31 * result + occurredAt.hashCode()
        result = 31 * result + (correlationId?.hashCode() ?: 0)
        result = 31 * result + (causationId?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "DurableEventEnvelope(stream=$stream, type=$type, key=$key, eventId=$eventId)"
    }
}

/**
 * Publishes business events to a durable event stream.
 */
interface DurableEventPublisher {
    suspend fun publish(event: DurableEventEnvelope)

    suspend fun publishAll(events: Iterable<DurableEventEnvelope>) {
        for (event in events) {
            publish(event)
        }
    }
}

/**
 * Handles one durable event delivered by a runtime-specific consumer.
 */
fun interface DurableEventHandler {
    suspend fun handle(event: DurableEventEnvelope)
}

/**
 * Consumes durable events from a stream.
 *
 * Implementations should acknowledge or commit an event only after [handler] returns successfully. If the handler
 * throws, retry, dead-letter, or stop behavior is defined by the backend configuration.
 */
interface DurableEventConsumer {
    suspend fun subscribe(
        stream: EventStreamName,
        handler: DurableEventHandler,
    ): DurableEventSubscription
}

/**
 * Combined durable event service for simple runtimes and tests.
 */
interface DurableEventBus : DurableEventPublisher, DurableEventConsumer

/**
 * Handle returned by durable event subscriptions.
 */
fun interface DurableEventSubscription : AutoCloseable {
    override fun close()
}
