package io.github.mikai233.asteria.core

import kotlin.reflect.KClass

/**
 * Framework-level description of a sharded entity type.
 *
 * The core spec only records portable metadata: kind, id type, role ownership, shard count,
 * handoff message, and extension attributes. Runtime adapters such as Pekko attach their own
 * attributes through extension DSL functions.
 */
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

/**
 * Framework-level description of a cluster singleton.
 *
 * The [role] declares where the singleton is allowed to be hosted. Runtime adapters may still
 * register a proxy on every node so callers can send messages without knowing the host node.
 */
data class SingletonSpec(
    val name: SingletonName,
    val role: RoleKey,
    val handoffMessage: Any? = null,
    val attributes: Map<String, Any> = emptyMap(),
)
