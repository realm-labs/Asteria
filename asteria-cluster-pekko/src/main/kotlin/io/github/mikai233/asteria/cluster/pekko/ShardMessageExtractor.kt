package io.github.mikai233.asteria.cluster.pekko

import io.github.mikai233.asteria.message.ShardMessage
import org.apache.pekko.cluster.sharding.ShardRegion

class ShardMessageExtractor(
    private val shardCount: Int,
    private val idResolver: (Any) -> Any? = { message -> (message as? ShardMessage<*>)?.id },
) : ShardRegion.MessageExtractor {
    init {
        require(shardCount > 0) { "shardCount must be greater than zero" }
    }

    override fun entityId(message: Any): String? {
        return idResolver(message)?.toString()
    }

    override fun entityMessage(message: Any): Any = message

    override fun shardId(message: Any): String? {
        val entityId = entityId(message) ?: return null
        return Math.floorMod(entityId.hashCode(), shardCount).toString()
    }
}
