package io.github.realmlabs.asteria.cluster.pekko

import io.github.realmlabs.asteria.rpc.MissingRpcEntityIdException
import io.github.realmlabs.asteria.rpc.RpcEntityIdRegistry

object PekkoRpcShardExtractors {
    fun byRpcEntityId(
        shardCount: Int,
        entityIds: RpcEntityIdRegistry,
    ): PekkoMessageExtractor<Any> {
        PekkoShardExtractors.validateShardCount(shardCount)
        return PekkoMessageExtractor(
            messageClass = Any::class.java,
            entityIdResolver = { message -> entityIds.entityId(message) ?: throw MissingRpcEntityIdException(message) },
            shardIdResolver = { _, entityId -> Math.floorMod(entityId.hashCode(), shardCount).toString() },
        )
    }
}
