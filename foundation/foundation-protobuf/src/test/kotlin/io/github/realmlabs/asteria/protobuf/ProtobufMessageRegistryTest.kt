package io.github.realmlabs.asteria.protobuf

import com.google.protobuf.StringValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProtobufMessageRegistryTest {
    @Test
    fun `registry encodes and decodes message by generic key`() {
        val registry = protobufMessageRegistry<String> {
            message("google.protobuf.StringValue", StringValue::class, StringValue.parser())
        }
        val message = StringValue.of("hello")

        val encoded = registry.encode(message)
        val decoded = registry.decode(encoded)

        assertEquals("google.protobuf.StringValue", encoded.key)
        assertEquals(message, decoded.message)
    }

    @Test
    fun `registry rejects duplicate keys`() {
        assertFailsWith<IllegalStateException> {
            protobufMessageRegistry<String> {
                message("string", StringValue::class, StringValue.parser())
                message("string", StringValue::class, StringValue.parser())
            }
        }
    }

    @Test
    fun `registry rejects unknown key`() {
        val registry = protobufMessageRegistry<String> {
            message("string", StringValue::class, StringValue.parser())
        }

        assertFailsWith<IllegalArgumentException> {
            registry.parserFor("missing")
        }
    }
}
