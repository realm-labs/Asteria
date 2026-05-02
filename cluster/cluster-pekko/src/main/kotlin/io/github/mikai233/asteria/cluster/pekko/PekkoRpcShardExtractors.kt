package io.github.mikai233.asteria.cluster.pekko

import io.github.mikai233.asteria.rpc.MissingRpcEntityIdException
import io.github.mikai233.asteria.rpc.RpcEntityIdRegistry

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
