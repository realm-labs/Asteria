package io.github.realmlabs.asteria.ephemeral.broadcast.protobuf

import com.google.protobuf.StringValue
import io.github.realmlabs.asteria.ephemeral.broadcast.EphemeralBroadcastTopic
import io.github.realmlabs.asteria.ephemeral.broadcast.LocalEphemeralBroadcastBus
import io.github.realmlabs.asteria.protobuf.protobufMessageRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

class ProtobufEphemeralBroadcastTest {
    @Test
    fun `ephemeral broadcast bus can publish and subscribe protobuf payloads`() {
        val registry = protobufMessageRegistry {
            message("google.protobuf.StringValue", StringValue::class, StringValue.parser())
        }
        val bus = LocalEphemeralBroadcastBus()
        val topic = EphemeralBroadcastTopic("world:1")
        val received = mutableListOf<StringValue>()

        bus.subscribeProto<StringValue>(topic, registry) { received += it }
        bus.publishProto(topic, registry, StringValue.of("hello"))

        assertEquals(listOf(StringValue.of("hello")), received)
    }

    @Test
    fun `protobuf subscriber ignores non protobuf ephemeral broadcast payloads`() {
        val registry = protobufMessageRegistry {
            message("google.protobuf.StringValue", StringValue::class, StringValue.parser())
        }
        val bus = LocalEphemeralBroadcastBus()
        val topic = EphemeralBroadcastTopic("world:1")
        val received = mutableListOf<StringValue>()

        bus.subscribeProto<StringValue>(topic, registry) { received += it }
        bus.publish(topic, "plain-message")

        assertEquals(emptyList(), received)
    }
}
