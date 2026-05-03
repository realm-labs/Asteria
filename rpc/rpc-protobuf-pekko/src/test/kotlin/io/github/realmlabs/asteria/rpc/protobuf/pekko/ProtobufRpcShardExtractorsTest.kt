package io.github.realmlabs.asteria.rpc.protobuf.pekko

import com.google.protobuf.StringValue
import io.github.realmlabs.asteria.rpc.protobuf.protobufRpcProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProtobufRpcShardExtractorsTest {
    @Test
    fun `extractor resolves entity id from protobuf rpc protocol`() {
        val protocol = protobufRpcProtocol {
            message(1001, StringValue.parser())
            entityId<StringValue> { it.value }
        }
        val extractor = ProtobufRpcShardExtractors.byEntityIdHash(16, protocol)
        val message = StringValue.of("player-1")

        assertEquals("player-1", extractor.entityId(message))
        assertEquals(message, extractor.entityMessage(message))
    }

    @Test
    fun `extractor supports custom shard id strategy`() {
        val protocol = protobufRpcProtocol {
            message(1001, StringValue.parser())
            entityId<StringValue> { it.value }
        }
        val extractor = ProtobufRpcShardExtractors.of(
            shardIdResolver = { message, entityId -> "${message::class.simpleName}:$entityId" },
            protocol = protocol,
        )

        assertEquals("StringValue:player-1", extractor.shardId(StringValue.of("player-1")))
    }

    @Test
    fun `extractor fails when message does not declare an entity id`() {
        val protocol = protobufRpcProtocol {
            message(1001, StringValue.parser())
        }
        val extractor = ProtobufRpcShardExtractors.byEntityIdHash(16, protocol)

        assertFailsWith<IllegalArgumentException> {
            extractor.entityId(StringValue.of("player-1"))
        }
    }
}
