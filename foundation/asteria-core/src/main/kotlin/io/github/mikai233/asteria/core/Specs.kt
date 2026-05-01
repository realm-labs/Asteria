package io.github.mikai233.asteria.core

import kotlin.reflect.KClass

data class EntitySpec<ID : Any>(
    val kind: EntityKind,
    val idType: KClass<ID>,
    val role: RoleKey? = null,
    val shardCount: Int = 100,
    val handoffMessage: Any? = null,
    val attributes: Map<String, Any> = emptyMap(),
) {
    init {
        require(shardCount > 0) { "shardCount must be greater than zero" }
    }
}

data class SingletonSpec(
    val name: SingletonName,
    val role: RoleKey,
    val handoffMessage: Any? = null,
    val attributes: Map<String, Any> = emptyMap(),
)
