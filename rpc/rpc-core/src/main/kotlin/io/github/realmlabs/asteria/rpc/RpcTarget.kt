package io.github.realmlabs.asteria.rpc

import io.github.realmlabs.asteria.core.EntityKind
import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.core.SingletonName

sealed interface RpcTarget {
    data class Entity(val kind: EntityKind) : RpcTarget
    data class Singleton(val name: SingletonName) : RpcTarget
    data class Service(val role: RoleKey, val path: String) : RpcTarget
}

enum class RpcMode {
    ASK,
    TELL,
}
