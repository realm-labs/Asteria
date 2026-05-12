package io.github.realmlabs.asteria.cluster.pekko

import org.apache.pekko.cluster.sharding.ShardRegion

/**
 * Type-checking Pekko [ShardRegion.MessageExtractor].
 *
 * The extractor rejects unexpected message types before resolver lambdas run, which makes route misconfiguration fail
 * at the sharding boundary instead of surfacing as unchecked casts inside application code.
 */
class PekkoMessageExtractor<M : Any>(
    private val messageClass: Class<M>,
    private val entityIdResolver: (M) -> String,
    private val shardIdResolver: (M, entityId: String) -> String,
    private val entityMessageResolver: (M) -> Any = { it },
) : ShardRegion.MessageExtractor {
    override fun entityId(message: Any): String {
        return entityIdResolver(cast(message))
    }

    override fun entityMessage(message: Any): Any {
        return entityMessageResolver(cast(message))
    }

    override fun shardId(message: Any): String {
        val typedMessage = cast(message)
        val entityId = entityIdResolver(typedMessage)
        return shardIdResolver(typedMessage, entityId)
    }

    private fun cast(message: Any): M {
        require(messageClass.isInstance(message)) {
            "shard message ${message::class.qualifiedName} must be ${messageClass.name}"
        }
        return messageClass.cast(message)
    }

    companion object {
        inline fun <reified M : Any> of(
            noinline entityIdResolver: (M) -> String,
            noinline shardIdResolver: (M, entityId: String) -> String,
            noinline entityMessageResolver: (M) -> Any = { it },
        ): PekkoMessageExtractor<M> {
            return PekkoMessageExtractor(
                M::class.java,
                entityIdResolver,
                shardIdResolver,
                entityMessageResolver,
            )
        }
    }
}
