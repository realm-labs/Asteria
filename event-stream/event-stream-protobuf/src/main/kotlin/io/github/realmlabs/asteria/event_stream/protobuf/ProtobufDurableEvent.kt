package io.github.realmlabs.asteria.event_stream.protobuf

import com.google.protobuf.GeneratedMessage
import io.github.realmlabs.asteria.event_stream.DurableEventDelivery
import io.github.realmlabs.asteria.event_stream.DurableEventEnvelope
import io.github.realmlabs.asteria.event_stream.DurableEventPublisher
import io.github.realmlabs.asteria.event_stream.DurableEventPublishResult
import io.github.realmlabs.asteria.event_stream.DurableEventSubscribeOptions
import io.github.realmlabs.asteria.event_stream.DurableEventSubscription
import io.github.realmlabs.asteria.event_stream.DurableEventType
import io.github.realmlabs.asteria.event_stream.DurableEventConsumer
import io.github.realmlabs.asteria.event_stream.EventStreamName
import io.github.realmlabs.asteria.protobuf.ProtobufMessageRegistry
import java.time.Instant
import java.util.UUID
import kotlin.reflect.KClass

/**
 * Encodes a protobuf message as a durable event envelope using a string-keyed registry.
 */
fun ProtobufMessageRegistry<String>.encodeDurableEvent(
    stream: EventStreamName,
    message: GeneratedMessage,
    key: String? = null,
    headers: Map<String, String> = emptyMap(),
    eventId: String = UUID.randomUUID().toString(),
    occurredAt: Instant = Instant.now(),
    correlationId: String? = null,
    causationId: String? = null,
): DurableEventEnvelope {
    val encoded = encode(message)
    return DurableEventEnvelope(
        stream = stream,
        type = DurableEventType(encoded.key),
        payload = encoded.payload,
        key = key,
        headers = headers,
        eventId = eventId,
        occurredAt = occurredAt,
        correlationId = correlationId,
        causationId = causationId,
    )
}

/**
 * Decodes a protobuf durable event envelope back to a generated message.
 */
fun ProtobufMessageRegistry<String>.decodeDurableEvent(event: DurableEventEnvelope): GeneratedMessage {
    return decode(event.type.value, event.payload).message
}

/**
 * Publishes a protobuf message as a durable event.
 */
suspend fun DurableEventPublisher.publishProto(
    stream: EventStreamName,
    registry: ProtobufMessageRegistry<String>,
    message: GeneratedMessage,
    key: String? = null,
    headers: Map<String, String> = emptyMap(),
    eventId: String = UUID.randomUUID().toString(),
    occurredAt: Instant = Instant.now(),
    correlationId: String? = null,
    causationId: String? = null,
): DurableEventPublishResult {
    return publish(
        registry.encodeDurableEvent(
            stream = stream,
            message = message,
            key = key,
            headers = headers,
            eventId = eventId,
            occurredAt = occurredAt,
            correlationId = correlationId,
            causationId = causationId,
        ),
    )
}

/**
 * Subscribes to protobuf durable event deliveries of type [M] and ignores other event types on the same stream.
 */
suspend inline fun <reified M : GeneratedMessage> DurableEventConsumer.subscribeProto(
    stream: EventStreamName,
    registry: ProtobufMessageRegistry<String>,
    options: DurableEventSubscribeOptions = DurableEventSubscribeOptions(),
    noinline handler: suspend (DurableEventDelivery, M) -> Unit,
): DurableEventSubscription {
    return subscribeProto(stream, registry, M::class, options, handler)
}

/**
 * Subscribes to protobuf durable event deliveries for [messageClass] and ignores other event types on the same stream.
 */
suspend fun <M : GeneratedMessage> DurableEventConsumer.subscribeProto(
    stream: EventStreamName,
    registry: ProtobufMessageRegistry<String>,
    messageClass: KClass<M>,
    options: DurableEventSubscribeOptions = DurableEventSubscribeOptions(),
    handler: suspend (DurableEventDelivery, M) -> Unit,
): DurableEventSubscription {
    val expectedType = registry.keyFor(messageClass.java)
    return subscribe(stream, options) { delivery ->
        if (delivery.event.type.value != expectedType) return@subscribe
        val message = registry.decodeDurableEvent(delivery)
        handler(delivery, messageClass.java.cast(message))
    }
}

private fun ProtobufMessageRegistry<String>.decodeDurableEvent(delivery: DurableEventDelivery): GeneratedMessage {
    return decodeDurableEvent(delivery.event)
}
