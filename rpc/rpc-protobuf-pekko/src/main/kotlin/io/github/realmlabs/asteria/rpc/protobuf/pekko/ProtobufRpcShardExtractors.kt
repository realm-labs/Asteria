package io.github.realmlabs.asteria.rpc.protobuf.pekko

import com.google.protobuf.GeneratedMessage
import io.github.realmlabs.asteria.cluster.pekko.PekkoMessageExtractor
import io.github.realmlabs.asteria.cluster.pekko.PekkoShardExtractors
import io.github.realmlabs.asteria.rpc.protobuf.ProtobufRpcProtocol
import io.github.realmlabs.asteria.rpc.protobuf.ProtobufRpcProtocols

/**
 * Pekko sharding extractors for protobuf RPC messages.
 */
object ProtobufRpcShardExtractors {
    /**
     * Builds an extractor that reads entity ids from [ProtobufRpcProtocol.entityIds].
     *
     * [shardIdResolver] receives the original protobuf message and resolved entity id, allowing deployments to keep the
     * same entity-id registry while changing shard placement policy.
     */
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

    /**
     * Routes protobuf RPC messages by hashing their registered entity id.
     */
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
