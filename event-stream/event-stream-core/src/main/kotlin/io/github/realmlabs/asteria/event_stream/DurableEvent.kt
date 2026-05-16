package io.github.realmlabs.asteria.event_stream

import java.time.Instant
import java.util.*

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
 * Consumer group or subscription name used by a backend to coordinate durable consumption.
 */
@JvmInline
value class EventStreamConsumerGroup(val value: String) {
    init {
        require(value.isNotBlank()) { "event stream consumer group must not be blank" }
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
 * Position used when a consumer starts reading a stream.
 */
sealed class DurableEventStartPosition {
    data object Latest : DurableEventStartPosition()

    data object Earliest : DurableEventStartPosition()

    data class FromTimestamp(val timestamp: Instant) : DurableEventStartPosition()

    data class FromOffset(val offset: String) : DurableEventStartPosition() {
        init {
            require(offset.isNotBlank()) { "event stream offset must not be blank" }
        }
    }
}

/**
 * Handler failure mode requested by a subscription.
 */
enum class DurableEventFailureMode {
    Retry,
    DeadLetter,
    Stop,
}

/**
 * Failure policy requested by a consumer subscription.
 *
 * Backends map this policy to their own retry, dead-letter, and stop controls.
 */
data class DurableEventFailurePolicy(
    val mode: DurableEventFailureMode = DurableEventFailureMode.Retry,
    val maxAttempts: Int? = null,
    val deadLetterStream: EventStreamName? = null,
) {
    init {
        require(maxAttempts == null || maxAttempts > 0) { "durable event maxAttempts must be positive" }
        require(mode == DurableEventFailureMode.DeadLetter || deadLetterStream == null) {
            "deadLetterStream requires DeadLetter failure mode"
        }
    }

    companion object {
        fun retry(maxAttempts: Int? = null): DurableEventFailurePolicy {
            return DurableEventFailurePolicy(mode = DurableEventFailureMode.Retry, maxAttempts = maxAttempts)
        }

        fun deadLetter(
            stream: EventStreamName? = null,
            maxAttempts: Int? = null,
        ): DurableEventFailurePolicy {
            return DurableEventFailurePolicy(
                mode = DurableEventFailureMode.DeadLetter,
                maxAttempts = maxAttempts,
                deadLetterStream = stream,
            )
        }

        fun stop(): DurableEventFailurePolicy {
            return DurableEventFailurePolicy(mode = DurableEventFailureMode.Stop)
        }
    }
}

/**
 * Consumer options for durable event subscriptions.
 */
data class DurableEventSubscribeOptions(
    val consumerGroup: EventStreamConsumerGroup? = null,
    val startPosition: DurableEventStartPosition = DurableEventStartPosition.Latest,
    val failurePolicy: DurableEventFailurePolicy = DurableEventFailurePolicy.retry(),
)

/**
 * Result returned after a backend accepts an event for durable publication.
 */
data class DurableEventPublishResult(
    val stream: EventStreamName,
    val eventId: String,
    val partition: String? = null,
    val offset: String? = null,
    val publishedAt: Instant = Instant.now(),
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(eventId.isNotBlank()) { "durable event publish result eventId must not be blank" }
        require(partition == null || partition.isNotBlank()) { "durable event partition must not be blank" }
        require(offset == null || offset.isNotBlank()) { "durable event offset must not be blank" }
        metadata.forEach { (name, _) ->
            require(name.isNotBlank()) { "durable event publish result metadata name must not be blank" }
        }
    }
}

/**
 * Event and backend metadata delivered to a handler.
 */
data class DurableEventDelivery(
    val event: DurableEventEnvelope,
    val consumerGroup: EventStreamConsumerGroup? = null,
    val partition: String? = null,
    val offset: String? = null,
    val attempt: Int = 1,
    val receivedAt: Instant = Instant.now(),
    val redelivered: Boolean = false,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(partition == null || partition.isNotBlank()) { "durable event partition must not be blank" }
        require(offset == null || offset.isNotBlank()) { "durable event offset must not be blank" }
        require(attempt > 0) { "durable event attempt must be positive" }
        metadata.forEach { (name, _) ->
            require(name.isNotBlank()) { "durable event delivery metadata name must not be blank" }
        }
    }
}

/**
 * Publishes business events to a durable event stream.
 *
 * A successful call means the backend accepted the event for durable publication. It does not imply that any consumer
 * has processed the event.
 */
interface DurableEventPublisher {
    suspend fun publish(event: DurableEventEnvelope): DurableEventPublishResult

    suspend fun publishAll(events: Iterable<DurableEventEnvelope>): List<DurableEventPublishResult> {
        val results = mutableListOf<DurableEventPublishResult>()
        for (event in events) {
            results += publish(event)
        }
        return results
    }
}

/**
 * Handles one durable event delivery.
 */
fun interface DurableEventHandler {
    suspend fun handle(delivery: DurableEventDelivery)
}

/**
 * Consumes durable events from a stream.
 *
 * The default delivery contract is at-least-once. Implementations should acknowledge or commit an event only after
 * [handler] returns successfully. If the handler throws, retry, dead-letter, or stop behavior is defined by the
 * subscription options and backend configuration.
 */
interface DurableEventConsumer {
    suspend fun subscribe(
        stream: EventStreamName,
        options: DurableEventSubscribeOptions = DurableEventSubscribeOptions(),
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
