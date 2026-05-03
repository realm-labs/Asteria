package io.github.realmlabs.asteria.rpc.protobuf

import com.google.protobuf.BoolValue
import com.google.protobuf.StringValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProtobufRpcProtocolTest {
    @Test
    fun `protobuf rpc protocol registers messages and entity id resolvers`() {
        val protocol = protobufRpcProtocol {
            message(1001, StringValue.parser())
            message(1002, BoolValue.parser())
            entityId<StringValue> { it.value }
        }

        assertEquals(1001, protocol.messages.keyFor(StringValue.of("p1")))
        assertEquals(StringValue.of("p1"), protocol.messages.decode(1001, StringValue.of("p1").toByteArray()).message)
        assertEquals("p1", protocol.entityIds.entityId(StringValue.of("p1")))
        assertEquals("p1", protocol.rpcProtocol().entityIds.entityId(StringValue.of("p1")))
    }

    @Test
    fun `protobuf rpc protocol supports contributors`() {
        val contributor = ProtobufRpcProtocolContributor { builder ->
            builder.message(2001, BoolValue.parser())
        }

        val protocol = protobufRpcProtocol {
            include(contributor)
        }

        assertEquals(2001, protocol.messages.keyFor(BoolValue.of(true)))
    }

    @Test
    fun `protobuf rpc protocol rejects duplicate message metadata`() {
        assertFailsWith<IllegalStateException> {
            protobufRpcProtocol {
                message<StringValue>(1001, StringValue.parser())
                message<BoolValue>(1001, BoolValue.parser())
            }
        }
        assertFailsWith<IllegalStateException> {
            protobufRpcProtocol {
                message<StringValue>(1001, StringValue.parser())
                message<StringValue>(1002, StringValue.parser())
            }
        }
    }
}
