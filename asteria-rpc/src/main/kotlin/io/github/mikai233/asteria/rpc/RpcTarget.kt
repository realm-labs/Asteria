package io.github.mikai233.asteria.rpc

import io.github.mikai233.asteria.core.EntityKind
import io.github.mikai233.asteria.core.RoleKey
import io.github.mikai233.asteria.core.SingletonName

sealed interface RpcTarget {
    data class Entity(
        val kind: EntityKind,
        val entityId: String,
    ) : RpcTarget

    data class Singleton(
        val name: SingletonName,
    ) : RpcTarget

    data class Service(
        val role: RoleKey,
        val path: String,
    ) : RpcTarget

    data object Local : RpcTarget
}
