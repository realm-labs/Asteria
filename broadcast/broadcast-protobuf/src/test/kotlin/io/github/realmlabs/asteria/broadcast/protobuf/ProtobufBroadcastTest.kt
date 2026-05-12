package io.github.realmlabs.asteria.broadcast.protobuf

import com.google.protobuf.StringValue
import io.github.realmlabs.asteria.broadcast.BroadcastTopic
import io.github.realmlabs.asteria.broadcast.LocalBroadcastBus
import io.github.realmlabs.asteria.protobuf.protobufMessageRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

class ProtobufBroadcastTest {
    @Test
    fun `broadcast bus can publish and subscribe protobuf payloads`() {
        val registry = protobufMessageRegistry {
            message("google.protobuf.StringValue", StringValue::class, StringValue.parser())
        }
        val bus = LocalBroadcastBus()
        val topic = BroadcastTopic("world:1")
        val received = mutableListOf<StringValue>()

        bus.subscribeProto<StringValue>(topic, registry) { received += it }
        bus.publishProto(topic, registry, StringValue.of("hello"))

        assertEquals(listOf(StringValue.of("hello")), received)
    }

    @Test
    fun `protobuf subscriber ignores non protobuf broadcast payloads`() {
        val registry = protobufMessageRegistry {
            message("google.protobuf.StringValue", StringValue::class, StringValue.parser())
        }
        val bus = LocalBroadcastBus()
        val topic = BroadcastTopic("world:1")
        val received = mutableListOf<StringValue>()

        bus.subscribeProto<StringValue>(topic, registry) { received += it }
        bus.publish(topic, "plain-message")

        assertEquals(emptyList(), received)
    }
}
