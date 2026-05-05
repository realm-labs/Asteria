package io.github.realmlabs.asteria.script

import io.github.realmlabs.asteria.core.EntityKind
import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.core.SingletonName

/**
 * Addressing model for script dispatch.
 *
 * Runtime implementations decide how each target is routed. The Pekko runtime treats [AllNodes] and [Role] as
 * node-level distributed pub-sub targets, [Node] as exact Pekko cluster address matches, [ActorPath] as direct actor
 * selections, [Entity] as sharded entity messages, and [Singleton] as a singleton registry lookup.
 */
sealed interface ScriptTarget {
    data object AllNodes : ScriptTarget

    data class Role(val role: RoleKey) : ScriptTarget

    /**
     * Exact node addresses as returned by the underlying cluster implementation.
     */
    data class Node(val addresses: List<String>) : ScriptTarget {
        init {
            require(addresses.isNotEmpty()) { "node addresses must not be empty" }
            require(addresses.all { it.isNotBlank() }) { "node addresses must not contain blank values" }
        }
    }

    /**
     * Actor selection paths. Each path is dispatched independently and should resolve on the receiving actor system.
     */
    data class ActorPath(val paths: List<String>) : ScriptTarget {
        init {
            require(paths.isNotEmpty()) { "actor paths must not be empty" }
            require(paths.all { it.isNotBlank() }) { "actor paths must not contain blank values" }
        }
    }

    /**
     * Sharded entity ids for one entity kind. Multi-id targets are expanded to one entity message per id.
     */
    data class Entity(
        val kind: EntityKind,
        val ids: List<String>,
    ) : ScriptTarget {
        init {
            require(ids.isNotEmpty()) { "entity ids must not be empty" }
            require(ids.all { it.isNotBlank() }) { "entity ids must not contain blank values" }
        }
    }

    data class Singleton(val name: SingletonName) : ScriptTarget
}
