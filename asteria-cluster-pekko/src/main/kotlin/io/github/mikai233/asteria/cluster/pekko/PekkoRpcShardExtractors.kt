package io.github.mikai233.asteria.cluster.pekko

import io.github.mikai233.asteria.rpc.RpcRouteRegistry
import io.github.mikai233.asteria.rpc.RpcTarget

object PekkoRpcShardExtractors {
    fun byRpcEntityTarget(
        shardCount: Int,
        routeRegistry: RpcRouteRegistry,
    ): PekkoMessageExtractor<Any> {
        PekkoShardExtractors.validateShardCount(shardCount)
        return PekkoMessageExtractor(
            messageClass = Any::class.java,
            entityIdResolver = { message -> entityTarget(message, routeRegistry).entityId },
            shardIdResolver = { _, entityId -> Math.floorMod(entityId.hashCode(), shardCount).toString() },
        )
    }

    private fun entityTarget(message: Any, routeRegistry: RpcRouteRegistry): RpcTarget.Entity {
        val target = routeRegistry.resolve(message) ?: error("rpc route for ${message::class.qualifiedName} not found")
        require(target is RpcTarget.Entity) {
            "rpc route for ${message::class.qualifiedName} must target entity, but was $target"
        }
        return target
    }
}
