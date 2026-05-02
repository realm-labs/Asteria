package io.github.mikai233.asteria.rpc.protobuf

import com.google.protobuf.BoolValue
import com.google.protobuf.Int32Value
import com.google.protobuf.StringValue
import io.github.mikai233.asteria.core.EntityKind
import io.github.mikai233.asteria.core.SingletonName
import io.github.mikai233.asteria.rpc.RpcMode
import io.github.mikai233.asteria.rpc.RpcTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProtobufRpcProtocolTest {
    @Test
    fun `protobuf rpc protocol registers ask method messages and entity id`() {
        val protocol = protobufRpcProtocol {
            entityCall<StringValue, Int32Value>(
                id = 1001,
                name = "player.query",
                target = RpcTarget.Entity(EntityKind("player")),
                requestParser = StringValue.parser(),
                responseId = 1002,
                responseParser = Int32Value.parser(),
                entityIdResolver = { it.value },
            )
        }
        val method = protocol.methods.methodFor(1001)

        assertEquals("player.query", method?.name)
        assertEquals(RpcMode.ASK, method?.mode)
        assertEquals(Int32Value::class, method?.responseType)
        assertEquals("p1", method?.resolveEntityId(StringValue.of("p1")))
        assertEquals("p1", protocol.entityIds.entityId(StringValue.of("p1")))
        assertEquals(StringValue.of("p1"), protocol.messages.decode(1001, StringValue.of("p1").toByteArray()).message)
    }

    @Test
    fun `protobuf rpc protocol registers tell method`() {
        val protocol = protobufRpcProtocol {
            tell<BoolValue>(
                id = 2001,
                name = "world.reload",
                target = RpcTarget.Singleton(SingletonName("world")),
                requestParser = BoolValue.parser(),
            )
        }
        val method = protocol.methods.methodNamed("world.reload")

        assertEquals(RpcMode.TELL, method?.mode)
        assertEquals(null, method?.responseType)
        assertEquals(RpcTarget.Singleton(SingletonName("world")), method?.target)
    }

    @Test
    fun `protobuf rpc protocol supports contributors`() {
        val contributor = ProtobufRpcProtocolContributor { builder ->
            builder.tell<BoolValue>(
                id = 2001,
                name = "world.reload",
                target = RpcTarget.Singleton(SingletonName("world")),
                requestParser = BoolValue.parser(),
            )
        }

        val protocol = protobufRpcProtocol {
            include(contributor)
        }

        assertEquals("world.reload", protocol.methods.methodFor(2001)?.name)
    }

    @Test
    fun `protobuf rpc protocol rejects duplicate request message metadata`() {
        assertFailsWith<IllegalStateException> {
            protobufRpcProtocol {
                tell<StringValue>(1001, "a", RpcTarget.Singleton(SingletonName("world")), StringValue.parser())
                tell<StringValue>(1002, "b", RpcTarget.Singleton(SingletonName("world")), StringValue.parser())
            }
        }
    }
}
