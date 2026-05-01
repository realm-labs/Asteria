package io.github.mikai233.asteria.script

import io.github.mikai233.asteria.core.EntityKind
import io.github.mikai233.asteria.core.RoleKey
import io.github.mikai233.asteria.core.SingletonName

sealed interface ScriptTarget {
    data object AllNodes : ScriptTarget

    data class Role(val role: RoleKey) : ScriptTarget

    data class Node(val addresses: List<String>) : ScriptTarget {
        init {
            require(addresses.isNotEmpty()) { "node addresses must not be empty" }
            require(addresses.all { it.isNotBlank() }) { "node addresses must not contain blank values" }
        }
    }

    data class ActorPath(val paths: List<String>) : ScriptTarget {
        init {
            require(paths.isNotEmpty()) { "actor paths must not be empty" }
            require(paths.all { it.isNotBlank() }) { "actor paths must not contain blank values" }
        }
    }

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
