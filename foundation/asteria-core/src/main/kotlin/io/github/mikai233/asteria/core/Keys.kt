package io.github.mikai233.asteria.core

@JvmInline
value class RoleKey(val value: String) {
    init {
        require(value.isNotBlank()) { "role key must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class EntityKind(val value: String) {
    init {
        require(value.isNotBlank()) { "entity kind must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class SingletonName(val value: String) {
    init {
        require(value.isNotBlank()) { "singleton name must not be blank" }
    }

    override fun toString(): String = value
}

data class NodeAddress(
    val host: String,
    val port: Int,
)

enum class NodeState {
    Unstarted,
    Starting,
    Started,
    Stopping,
    Stopped,
}
