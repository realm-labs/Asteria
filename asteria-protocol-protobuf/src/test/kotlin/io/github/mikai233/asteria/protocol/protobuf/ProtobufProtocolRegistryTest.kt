package io.github.mikai233.asteria.protocol.protobuf

import com.google.protobuf.StringValue
import kotlin.test.Test
import kotlin.test.assertEquals

class ProtobufProtocolRegistryTest {
    @Test
    fun `protocol registry delegates message encoding to protobuf registry`() {
        val registry = ProtobufProtocolRegistry(
            listOf(
                ProtoMapping(1001, StringValue::class, StringValue.parser()),
            ),
        )
        val message = StringValue.of("hello")

        val frame = registry.encode(message)
        val envelope = registry.decode(frame)

        assertEquals(1001, frame.id)
        assertEquals(1001, envelope.id)
        assertEquals(message, envelope.message)
    }
}
