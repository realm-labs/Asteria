package io.github.realmlabs.asteria.event_stream.nats

import io.github.realmlabs.asteria.event_stream.DurableEventBus
import io.github.realmlabs.asteria.event_stream.DurableEventDelivery
import io.github.realmlabs.asteria.event_stream.DurableEventEnvelope
import io.github.realmlabs.asteria.event_stream.DurableEventFailureMode
import io.github.realmlabs.asteria.event_stream.DurableEventHandler
import io.github.realmlabs.asteria.event_stream.DurableEventPublishResult
import io.github.realmlabs.asteria.event_stream.DurableEventStartPosition
import io.github.realmlabs.asteria.event_stream.DurableEventSubscribeOptions
import io.github.realmlabs.asteria.event_stream.DurableEventSubscription
import io.github.realmlabs.asteria.event_stream.DurableEventType
import io.github.realmlabs.asteria.event_stream.EventStreamName
import io.nats.client.Connection
import io.nats.client.Dispatcher
import io.nats.client.JetStream
import io.nats.client.JetStreamSubscription
import io.nats.client.Message
import io.nats.client.PushSubscribeOptions
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.api.DeliverPolicy
import io.nats.client.impl.Headers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.coroutines.CoroutineContext

/**
 * NATS JetStream implementation of [DurableEventBus].
 *
 * The adapter maps [EventStreamName] to a NATS subject for publishing and to a JetStream stream name for consumer
 * creation. It uses explicit acknowledgments: messages are acked only after the handler returns successfully.
 */
class NatsJetStreamEventBus(
    private val connection: Connection,
    private val options: NatsJetStreamEventBusOptions = NatsJetStreamEventBusOptions(),
) : DurableEventBus, AutoCloseable {
    private val logger = LoggerFactory.getLogger(NatsJetStreamEventBus::class.java)
    private val jetStream: JetStream = connection.jetStream()
    private val scope = CoroutineScope(SupervisorJob() + options.coroutineContext)

    override suspend fun publish(event: DurableEventEnvelope): DurableEventPublishResult {
        val subject = options.subjectFor(event.stream)
        val ack = withContext(options.blockingContext) {
            jetStream.publish(subject, event.toHeaders(), event.payload)
        }
        return DurableEventPublishResult(
            stream = event.stream,
            eventId = event.eventId,
            offset = ack.getSeqno().toString(),
            publishedAt = Instant.now(),
            metadata = buildMap {
                put("nats.stream", ack.getStream())
                put("nats.seqno", ack.getSeqno().toString())
                val domain: String? = ack.getDomain()
                domain?.let { put("nats.domain", it) }
                put("nats.duplicate", ack.isDuplicate().toString())
            },
        )
    }

    override suspend fun subscribe(
        stream: EventStreamName,
        options: DurableEventSubscribeOptions,
        handler: DurableEventHandler,
    ): DurableEventSubscription {
        val subject = this.options.subjectFor(stream)
        val dispatcher = connection.createDispatcher()
        val natsOptions = options.toPushSubscribeOptions(stream, subject, this.options)
        val subscription = withContext(this.options.blockingContext) {
            val group = options.consumerGroup?.value
            if (group == null) {
                jetStream.subscribe(subject, dispatcher, messageHandler(stream, options, handler), false, natsOptions)
            } else {
                jetStream.subscribe(subject, group, dispatcher, messageHandler(stream, options, handler), false, natsOptions)
            }
        }
        return NatsJetStreamEventSubscription(connection, dispatcher, subscription)
    }

    override fun close() {
        scope.cancel()
        if (options.closeConnectionOnClose) {
            connection.close()
        }
    }

    private fun messageHandler(
        stream: EventStreamName,
        subscribeOptions: DurableEventSubscribeOptions,
        handler: DurableEventHandler,
    ): (Message) -> Unit {
        return { message ->
            if (!message.isStatusMessage) {
                scope.launch {
                    try {
                        handler.handle(message.toDelivery(stream, subscribeOptions))
                        ack(message)
                    } catch (error: Throwable) {
                        handleFailure(message, stream, subscribeOptions, error)
                    }
                }
            }
        }
    }

    private suspend fun ack(message: Message) {
        withContext(options.blockingContext) {
            val timeout = options.ackSyncTimeout
            if (timeout == null) {
                message.ack()
            } else {
                message.ackSync(timeout)
            }
        }
    }

    private suspend fun handleFailure(
        message: Message,
        stream: EventStreamName,
        subscribeOptions: DurableEventSubscribeOptions,
        error: Throwable,
    ) {
        logger.error("NATS JetStream durable event handler failed", error)
        withContext(options.blockingContext) {
            when (subscribeOptions.failurePolicy.mode) {
                DurableEventFailureMode.Retry -> message.nak()
                DurableEventFailureMode.Stop -> message.term()
                DurableEventFailureMode.DeadLetter -> {
                    val deadLetterStream = subscribeOptions.failurePolicy.deadLetterStream
                    if (deadLetterStream == null) {
                        message.term()
                    } else {
                        publishDeadLetter(message, stream, deadLetterStream, error)
                        message.term()
                    }
                }
            }
        }
    }

    private fun publishDeadLetter(
        message: Message,
        stream: EventStreamName,
        deadLetterStream: EventStreamName,
        error: Throwable,
    ) {
        val event = message.toEvent(stream)
        val deadLetterEvent = event.copy(
            stream = deadLetterStream,
            headers = event.headers + mapOf(
                "asteria.failure.type" to error::class.qualifiedName.orEmpty(),
                "asteria.failure.message" to error.message.orEmpty(),
            ),
        )
        val subject = options.subjectFor(deadLetterStream)
        jetStream.publish(subject, deadLetterEvent.toHeaders(), deadLetterEvent.payload)
    }

    private fun DurableEventEnvelope.toHeaders(): Headers {
        val headers = Headers()
        this.headers.forEach { (name, value) -> headers.put(name, value) }
        headers.put(HeaderEventType, type.value)
        headers.put(HeaderEventId, eventId)
        headers.put(HeaderOccurredAt, occurredAt.toString())
        key?.let { headers.put(HeaderEventKey, it) }
        correlationId?.let { headers.put(HeaderCorrelationId, it) }
        causationId?.let { headers.put(HeaderCausationId, it) }
        return headers
    }

    private fun Message.toDelivery(
        stream: EventStreamName,
        subscribeOptions: DurableEventSubscribeOptions,
    ): DurableEventDelivery {
        val metadata = metaData()
        val attempt = metadata?.deliveredCount()?.toInt()?.takeIf { it > 0 } ?: 1
        return DurableEventDelivery(
            event = toEvent(stream),
            consumerGroup = subscribeOptions.consumerGroup,
            partition = metadata?.getStream(),
            offset = metadata?.streamSequence()?.toString(),
            attempt = attempt,
            receivedAt = Instant.now(),
            redelivered = attempt > 1,
            metadata = buildMap {
                put("nats.subject", subject)
                metadata?.getStream()?.let { put("nats.stream", it) }
                metadata?.getConsumer()?.let { put("nats.consumer", it) }
                metadata?.consumerSequence()?.let { put("nats.consumer_sequence", it.toString()) }
                metadata?.pendingCount()?.let { put("nats.pending", it.toString()) }
            },
        )
    }

    private fun Message.toEvent(stream: EventStreamName): DurableEventEnvelope {
        val headers = getHeaders()
        val type = headers.required(HeaderEventType)
        val eventId = headers.required(HeaderEventId)
        val occurredAt = headers.getFirst(HeaderOccurredAt)?.let(Instant::parse) ?: Instant.now()
        val eventHeaders = headers.toSingleValueMap() - ReservedHeaders
        return DurableEventEnvelope(
            stream = stream,
            type = DurableEventType(type),
            payload = data,
            key = headers.getFirst(HeaderEventKey),
            headers = eventHeaders,
            eventId = eventId,
            occurredAt = occurredAt,
            correlationId = headers.getFirst(HeaderCorrelationId),
            causationId = headers.getFirst(HeaderCausationId),
        )
    }
}

/**
 * Options for [NatsJetStreamEventBus].
 */
data class NatsJetStreamEventBusOptions(
    val subjectFor: (EventStreamName) -> String = { it.value },
    val streamNameFor: (EventStreamName) -> String = { it.value },
    val ackSyncTimeout: Duration? = null,
    val closeConnectionOnClose: Boolean = false,
    val coroutineContext: CoroutineContext = Dispatchers.IO,
    val blockingContext: CoroutineContext = Dispatchers.IO,
)

private class NatsJetStreamEventSubscription(
    private val connection: Connection,
    private val dispatcher: Dispatcher,
    private val subscription: JetStreamSubscription,
) : DurableEventSubscription {
    override fun close() {
        subscription.unsubscribe()
        connection.closeDispatcher(dispatcher)
    }
}

private fun DurableEventSubscribeOptions.toPushSubscribeOptions(
    stream: EventStreamName,
    subject: String,
    busOptions: NatsJetStreamEventBusOptions,
): PushSubscribeOptions {
    val builder = ConsumerConfiguration.builder()
        .ackPolicy(AckPolicy.Explicit)
        .filterSubject(subject)
    consumerGroup?.let {
        builder.durable(it.value)
        builder.deliverGroup(it.value)
    }
    failurePolicy.maxAttempts?.let { builder.maxDeliver(it.toLong()) }
    when (val position = startPosition) {
        DurableEventStartPosition.Earliest -> builder.deliverPolicy(DeliverPolicy.All)
        DurableEventStartPosition.Latest -> builder.deliverPolicy(DeliverPolicy.New)
        is DurableEventStartPosition.FromOffset -> {
            builder.deliverPolicy(DeliverPolicy.ByStartSequence)
            builder.startSequence(position.offset.toLong())
        }
        is DurableEventStartPosition.FromTimestamp -> {
            builder.deliverPolicy(DeliverPolicy.ByStartTime)
            builder.startTime(ZonedDateTime.ofInstant(position.timestamp, ZoneOffset.UTC))
        }
    }
    return builder.buildPushSubscribeOptions(busOptions.streamNameFor(stream))
}

private fun Headers?.required(name: String): String {
    return this?.getFirst(name) ?: error("NATS JetStream durable event header $name not found")
}

private fun Headers?.toSingleValueMap(): Map<String, String> {
    if (this == null) return emptyMap()
    return buildMap {
        this@toSingleValueMap.forEach { name, values ->
            values.firstOrNull()?.let { put(name, it) }
        }
    }
}

private const val HeaderEventType = "Asteria-Event-Type"
private const val HeaderEventId = "Asteria-Event-Id"
private const val HeaderEventKey = "Asteria-Event-Key"
private const val HeaderOccurredAt = "Asteria-Event-Occurred-At"
private const val HeaderCorrelationId = "Asteria-Event-Correlation-Id"
private const val HeaderCausationId = "Asteria-Event-Causation-Id"

private val ReservedHeaders = setOf(
    HeaderEventType,
    HeaderEventId,
    HeaderEventKey,
    HeaderOccurredAt,
    HeaderCorrelationId,
    HeaderCausationId,
)
