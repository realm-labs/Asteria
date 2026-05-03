package io.github.realmlabs.asteria.rpc.protobuf.pekko

import com.google.protobuf.BoolValue
import com.google.protobuf.StringValue
import io.github.realmlabs.asteria.rpc.protobuf.protobufRpcProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProtobufRpcMessageSerializerTest {
    @Test
    fun `serializer uses protobuf rpc message id as manifest`() {
        val serializer = ProtobufRpcMessageSerializer(
            protobufRpcProtocol {
                message(1001, StringValue.parser())
            },
        )
        val message = StringValue.of("hello")

        val manifest = serializer.manifest(message)
        val bytes = serializer.toBinary(message)
        val decoded = serializer.fromBinary(bytes, manifest)

        assertEquals("1001", manifest)
        assertEquals(message, decoded)
    }

    @Test
    fun `serializer rejects unregistered protobuf messages`() {
        val serializer = ProtobufRpcMessageSerializer(
            protobufRpcProtocol {
                message(1001, StringValue.parser())
            },
        )

        assertFailsWith<IllegalArgumentException> {
            serializer.manifest(BoolValue.of(true))
        }
    }
}
