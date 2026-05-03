package io.github.realmlabs.asteria.rpc.protobuf.pekko

import com.google.protobuf.GeneratedMessage
import io.github.realmlabs.asteria.cluster.pekko.PekkoMessageExtractor
import io.github.realmlabs.asteria.cluster.pekko.PekkoShardExtractors
import io.github.realmlabs.asteria.rpc.protobuf.ProtobufRpcProtocol
import io.github.realmlabs.asteria.rpc.protobuf.ProtobufRpcProtocols

object ProtobufRpcShardExtractors {
    fun of(
        shardIdResolver: (GeneratedMessage, entityId: String) -> String,
        protocol: ProtobufRpcProtocol = ProtobufRpcProtocols.load(),
        entityMessageResolver: (GeneratedMessage) -> Any = { it },
    ): PekkoMessageExtractor<GeneratedMessage> {
        return PekkoMessageExtractor(
            messageClass = GeneratedMessage::class.java,
            entityIdResolver = { protocol.entityIds.requireEntityId(it) },
            shardIdResolver = shardIdResolver,
            entityMessageResolver = entityMessageResolver,
        )
    }

    fun byEntityIdHash(
        shardCount: Int,
        protocol: ProtobufRpcProtocol = ProtobufRpcProtocols.load(),
    ): PekkoMessageExtractor<GeneratedMessage> {
        PekkoShardExtractors.validateShardCount(shardCount)
        return of(
            shardIdResolver = { _, entityId -> Math.floorMod(entityId.hashCode(), shardCount).toString() },
            protocol = protocol,
        )
    }
}
