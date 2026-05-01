package io.github.mikai233.asteria.script

import io.github.mikai233.asteria.core.EntityKind
import io.github.mikai233.asteria.core.RoleKey
import io.github.mikai233.asteria.core.SingletonName

sealed interface ScriptTarget {
    data object AllNodes : ScriptTarget

    data class Role(val role: RoleKey) : ScriptTarget

    data class Node(val address: String) : ScriptTarget {
        init {
            require(address.isNotBlank()) { "node address must not be blank" }
        }
    }

    data class ActorPath(val path: String) : ScriptTarget {
        init {
            require(path.isNotBlank()) { "actor path must not be blank" }
        }
    }

    data class Entity(
        val kind: EntityKind,
        val id: String,
    ) : ScriptTarget {
        init {
            require(id.isNotBlank()) { "entity id must not be blank" }
        }
    }

    data class Singleton(val name: SingletonName) : ScriptTarget
}
