package io.github.mikai233.asteria.core

import java.io.Serializable

/**
 * Logical capability owned by a node, such as `gateway`, `player`, or `match`.
 *
 * Roles are used by runtime modules to decide where sharded entities and singleton hosts may run.
 * A role is not a deployment group by itself; the concrete runtime startup decides which roles the
 * current process owns.
 */
@JvmInline
value class RoleKey(val value: String) : Serializable {
    init {
        require(value.isNotBlank()) { "role key must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Stable name of a sharded entity type.
 *
 * All entities with the same kind share one shard region/proxy registry entry.
 */
@JvmInline
value class EntityKind(val value: String) {
    init {
        require(value.isNotBlank()) { "entity kind must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Stable name of a cluster singleton.
 *
 * The name is used both for framework registration and for the underlying singleton actor path.
 */
@JvmInline
value class SingletonName(val value: String) {
    init {
        require(value.isNotBlank()) { "singleton name must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Network address of a running node.
 */
data class NodeAddress(
    val host: String,
    val port: Int,
)

/**
 * Lifecycle state of an [AsteriaApplication].
 */
enum class NodeState {
    Unstarted,
    Starting,
    Started,
    Stopping,
    Stopped,
}
