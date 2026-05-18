package io.github.realmlabs.asteria.event.stream.protobuf

import com.google.protobuf.Int32Value
import com.google.protobuf.StringValue
import io.github.realmlabs.asteria.event.stream.EventStreamName
import io.github.realmlabs.asteria.event.stream.InMemoryDurableEventBus
import io.github.realmlabs.asteria.protobuf.protobufMessageRegistry
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ProtobufDurableEventTest {
    @Test
    fun `publisher can publish protobuf durable event`() = runBlocking {
        val registry = protobufMessageRegistry {
            message("google.protobuf.StringValue", StringValue::class, StringValue.parser())
        }
        val bus = InMemoryDurableEventBus()
        val stream = EventStreamName("orders")
        val received = mutableListOf<StringValue>()

        bus.subscribeProto<StringValue>(stream, registry) { _, message ->
            received += message
        }
        val result = bus.publishProto(stream, registry, StringValue.of("created"), key = "order-1")

        assertEquals("0", result.offset)
        assertEquals(listOf(StringValue.of("created")), received)
        assertEquals("google.protobuf.StringValue", bus.events(stream).single().type.value)
    }

    @Test
    fun `protobuf subscriber ignores other durable event types`() = runBlocking {
        val registry = protobufMessageRegistry {
            message("google.protobuf.StringValue", StringValue::class, StringValue.parser())
            message("google.protobuf.Int32Value", Int32Value::class, Int32Value.parser())
        }
        val bus = InMemoryDurableEventBus()
        val stream = EventStreamName("orders")
        val received = mutableListOf<StringValue>()

        bus.subscribeProto<StringValue>(stream, registry) { _, message ->
            received += message
        }
        bus.publishProto(stream, registry, Int32Value.of(7))

        assertEquals(emptyList(), received)
    }
}
