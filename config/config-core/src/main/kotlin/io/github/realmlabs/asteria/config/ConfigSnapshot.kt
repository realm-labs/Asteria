package io.github.realmlabs.asteria.config

import java.io.Serializable
import kotlin.reflect.KClass

/**
 * Version marker for a loaded config snapshot.
 *
 * [version] should change whenever config content changes. [checksum] can be used by tooling to
 * detect whether two versions contain identical data.
 */
data class ConfigRevision(
    val version: String,
    val checksum: String? = null,
) : Serializable {
    init {
        require(version.isNotBlank()) { "config revision version must not be blank" }
        require(checksum == null || checksum.isNotBlank()) { "config revision checksum must not be blank" }
    }
}

/**
 * Immutable view of all loaded config data.
 *
 * A snapshot may contain named [ConfigTable] instances and arbitrary typed components.
 *
 * Tables are the raw config data. Components are process-local read models built from those tables before the snapshot
 * is published, such as indexes, grouped lookups, timelines, or precomputed weight tables.
 */
interface ConfigSnapshot {
    /**
     * Version information for this snapshot.
     */
    val revision: ConfigRevision

    /**
     * Returns an untyped table by name.
     */
    fun table(name: ConfigTableName): ConfigTable<*, *>?

    /**
     * Returns all tables in this snapshot.
     */
    fun tables(): Collection<ConfigTable<*, *>>

    /**
     * Returns a typed runtime component by exact [type].
     */
    fun <T : Any> component(type: KClass<T>): T?

    /**
     * Returns all registered runtime components.
     */
    fun components(): Collection<Any>
}

/**
 * Default immutable [ConfigSnapshot] implementation.
 *
 * Duplicate table names or component types fail during construction so reload errors are caught
 * before a snapshot is published to readers.
 */
class DefaultConfigSnapshot(
    override val revision: ConfigRevision,
    tables: Iterable<ConfigTable<*, *>> = emptyList(),
    components: Iterable<Any> = emptyList(),
    componentsByType: Map<KClass<*>, Any> = emptyMap(),
) : ConfigSnapshot {
    private val tablesByName: Map<ConfigTableName, ConfigTable<*, *>> = tables.associateUniqueBy(
        keyOf = { it.name },
        errorOf = { "duplicate config table ${it.name}" },
    )
    private val componentsByType: Map<KClass<*>, Any> = components.associateUniqueBy(
        keyOf = { it::class },
        errorOf = { "duplicate config component ${it::class.qualifiedName}" },
    ) + componentsByType

    override fun table(name: ConfigTableName): ConfigTable<*, *>? {
        return tablesByName[name]
    }

    override fun tables(): Collection<ConfigTable<*, *>> {
        return tablesByName.values
    }

    override fun <T : Any> component(type: KClass<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return componentsByType[type] as T?
    }

    override fun components(): Collection<Any> {
        return componentsByType.values
    }
}

/**
 * Returns an untyped table or throws with revision context.
 */
fun ConfigSnapshot.requireAnyTable(name: ConfigTableName): ConfigTable<*, *> {
    return table(name) ?: error("config table $name not found in revision ${revision.version}")
}

/**
 * Returns a typed table by [name], or `null` when it is absent.
 *
 * The stored key and row types must match [K] and [R].
 */
inline fun <reified K : Any, reified R : Any> ConfigSnapshot.table(name: ConfigTableName): ConfigTable<K, R>? {
    val table = table(name) ?: return null
    require(table.keyType == K::class) {
        "config table $name key type mismatch, expected ${K::class.qualifiedName}, actual ${table.keyType.qualifiedName}"
    }
    require(table.rowType == R::class) {
        "config table $name row type mismatch, expected ${R::class.qualifiedName}, actual ${table.rowType.qualifiedName}"
    }
    @Suppress("UNCHECKED_CAST")
    return table as ConfigTable<K, R>
}

/**
 * Returns a typed table by [name] or throws with revision context.
 */
inline fun <reified K : Any, reified R : Any> ConfigSnapshot.requireTable(name: ConfigTableName): ConfigTable<K, R> {
    return table<K, R>(name) ?: error("config table $name not found in revision ${revision.version}")
}

/**
 * Returns a component by reified type, or `null` when it is absent.
 */
inline fun <reified T : Any> ConfigSnapshot.component(): T? {
    return component(T::class)
}

/**
 * Returns a component by reified type or throws with revision context.
 */
inline fun <reified T : Any> ConfigSnapshot.requireComponent(): T {
    return component<T>()
        ?: error("config component ${T::class.qualifiedName} not found in revision ${revision.version}")
}

private fun <K : Any, V : Any> Iterable<V>.associateUniqueBy(
    keyOf: (V) -> K,
    errorOf: (V) -> String,
): Map<K, V> {
    val result = linkedMapOf<K, V>()
    for (value in this) {
        val key = keyOf(value)
        require(!result.containsKey(key)) { errorOf(value) }
        result[key] = value
    }
    return result
}
