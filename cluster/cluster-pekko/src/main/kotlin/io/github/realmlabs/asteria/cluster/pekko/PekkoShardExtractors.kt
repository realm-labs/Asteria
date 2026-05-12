package io.github.realmlabs.asteria.cluster.pekko

import io.github.realmlabs.asteria.message.ShardMessage

/**
 * Factory methods for common Pekko sharding message extractors.
 */
object PekkoShardExtractors {
    /**
     * Extracts [ShardMessage.id] as the entity id and hashes it to a shard.
     */
    fun shardMessageByEntityIdHash(shardCount: Int): PekkoMessageExtractor<ShardMessage<*>> {
        validateShardCount(shardCount)
        return PekkoMessageExtractor(
            messageClass = ShardMessage::class.java,
            entityIdResolver = { it.id.toString() },
            shardIdResolver = { _, entityId -> entityId.hashShard(shardCount) },
        )
    }

    /**
     * Extracts a `Long` [ShardMessage.id] and maps it to a shard with modulo arithmetic.
     */
    fun longShardMessageByModulo(shardCount: Int): PekkoMessageExtractor<ShardMessage<Long>> {
        validateShardCount(shardCount)
        return PekkoMessageExtractor(
            messageClass = shardMessageClass(),
            entityIdResolver = { it.id.toString() },
            shardIdResolver = { message, _ -> Math.floorMod(message.id, shardCount.toLong()).toString() },
        )
    }

    /**
     * Builds an extractor for arbitrary messages whose entity id can be represented as a string.
     */
    inline fun <reified M : Any> byEntityIdHash(
        shardCount: Int,
        noinline entityIdResolver: (M) -> String,
        noinline entityMessageResolver: (M) -> Any = { it },
    ): PekkoMessageExtractor<M> {
        validateShardCount(shardCount)
        return PekkoMessageExtractor.of(
            entityIdResolver = entityIdResolver,
            shardIdResolver = { _, entityId -> Math.floorMod(entityId.hashCode(), shardCount).toString() },
            entityMessageResolver = entityMessageResolver,
        )
    }

    /**
     * Builds an extractor for arbitrary messages whose entity id is a numeric value.
     */
    inline fun <reified M : Any> byLongModulo(
        shardCount: Int,
        noinline entityIdResolver: (M) -> Long,
        noinline entityMessageResolver: (M) -> Any = { it },
    ): PekkoMessageExtractor<M> {
        validateShardCount(shardCount)
        return PekkoMessageExtractor.of(
            entityIdResolver = { entityIdResolver(it).toString() },
            shardIdResolver = { message, _ ->
                Math.floorMod(entityIdResolver(message), shardCount.toLong()).toString()
            },
            entityMessageResolver = entityMessageResolver,
        )
    }

    /**
     * Validates shard counts before they are passed to Pekko sharding.
     */
    fun validateShardCount(shardCount: Int) {
        require(shardCount > 0) { "shardCount must be greater than zero" }
    }

    private fun String.hashShard(shardCount: Int): String {
        return Math.floorMod(hashCode(), shardCount).toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun shardMessageClass(): Class<ShardMessage<Long>> {
        return ShardMessage::class.java as Class<ShardMessage<Long>>
    }
}
