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
     * Returns an untyped table by name, or `null` when the table is absent.
     */
    fun table(name: ConfigTableName): ConfigTable<*>?

    /**
     * Returns a table by exact runtime [type].
     *
     * This lookup is intentionally strict: it matches the concrete table class registered in the snapshot rather than
     * generic supertypes such as [MapConfigTable] or [ConfigTable].
     */
    fun <T : ConfigTable<*>> table(type: KClass<T>): T?

    /**
     * Returns all tables in this snapshot.
     */
    fun tables(): Collection<ConfigTable<*>>

    /**
     * Returns a typed runtime component by exact [type].
     *
     * Component lookup is also exact and does not search by interface hierarchy.
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
    entries: Iterable<SnapshotEntry> = emptyList(),
) : ConfigSnapshot {
    constructor(
        revision: ConfigRevision,
        tables: Iterable<ConfigTable<*>> = emptyList(),
        components: Iterable<Any> = emptyList(),
    ) : this(
        revision = revision,
        entries = tables.map { SnapshotEntry.Table(it) } +
                components.map { SnapshotEntry.Component(it) },
    )

    private val explicitTablesByType: Map<KClass<out ConfigTable<*>>, ConfigTable<*>> = entries
        .filterIsInstance<SnapshotEntry.Table>()
        .mapNotNull { entry -> entry.type?.let { it to entry.value } }
        .associateUniqueBy(
            keyOf = { it.first },
            errorOf = { "duplicate config table type ${it.first.qualifiedName}" },
        )
        .mapValues { it.value.second }
    private val explicitComponentsByType: Map<KClass<*>, Any> = entries
        .filterIsInstance<SnapshotEntry.Component>()
        .associateUniqueBy(
            keyOf = { it.type },
            errorOf = { "duplicate config component ${it.type.qualifiedName}" },
        )
        .mapValues { it.value.value }
    private val tables: List<ConfigTable<*>> = entries
        .filterIsInstance<SnapshotEntry.Table>()
        .map { it.value }
        .distinctByIdentity()
    private val components: List<Any> = entries
        .filterIsInstance<SnapshotEntry.Component>()
        .map { it.value }
        .distinctByIdentity()
    private val tablesByName: Map<ConfigTableName, ConfigTable<*>> = tables.associateUniqueBy(
        keyOf = { it.name },
        errorOf = { "duplicate config table ${it.name}" },
    )
    private val tablesByType: Map<KClass<out ConfigTable<*>>, ConfigTable<*>> =
        tables.associateUniqueTableSubclasses() + explicitTablesByType.validateTableTypes()
    private val componentsByType: Map<KClass<*>, Any> = components.associateUniqueBy(
        keyOf = { it::class },
        errorOf = { "duplicate config component ${it::class.qualifiedName}" },
    ) + explicitComponentsByType

    override fun table(name: ConfigTableName): ConfigTable<*>? {
        return tablesByName[name]
    }

    override fun <T : ConfigTable<*>> table(type: KClass<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return tablesByType[type] as T?
    }

    override fun tables(): Collection<ConfigTable<*>> {
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
 * Typed table or component entry for [DefaultConfigSnapshot].
 *
 * Explicit [type] values are useful when code generators want lookup by an abstract table class instead of by the
 * runtime implementation class alone.
 */
sealed interface SnapshotEntry {
    data class Table(
        val value: ConfigTable<*>,
        val type: KClass<out ConfigTable<*>>? = null,
    ) : SnapshotEntry {
        init {
            require(type == null || type.isInstance(value)) {
                "config table ${value.name} is not an instance of ${type?.qualifiedName}"
            }
        }
    }

    data class Component(
        val value: Any,
        val type: KClass<*> = value::class,
    ) : SnapshotEntry {
        init {
            require(type.isInstance(value)) {
                "config component ${value::class.qualifiedName} is not an instance of ${type.qualifiedName}"
            }
        }
    }
}

/**
 * Returns a table by exact runtime type or throws with revision context.
 */
inline fun <reified T : ConfigTable<*>> ConfigSnapshot.table(): T {
    return table(T::class)
        ?: error("config table ${T::class.qualifiedName} not found in revision ${revision.version}")
}

/**
 * Returns a component by reified type or throws with revision context.
 */
inline fun <reified T : Any> ConfigSnapshot.component(): T {
    return component(T::class)
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

private fun Iterable<ConfigTable<*>>.associateUniqueTableSubclasses(): Map<KClass<out ConfigTable<*>>, ConfigTable<*>> {
    val result = linkedMapOf<KClass<out ConfigTable<*>>, ConfigTable<*>>()
    for (table in this) {
        val type = table::class
        if (type.isGenericConfigTableImplementation()) {
            continue
        }
        require(!result.containsKey(type)) {
            "duplicate config table type ${type.qualifiedName}"
        }
        result[type] = table
    }
    return result
}

private fun <T : Any> Iterable<T>.distinctByIdentity(): List<T> {
    val seen = java.util.IdentityHashMap<T, Unit>()
    val result = mutableListOf<T>()
    for (value in this) {
        if (seen.put(value, Unit) == null) {
            result += value
        }
    }
    return result
}

private fun KClass<out ConfigTable<*>>.isGenericConfigTableImplementation(): Boolean {
    return this == MapConfigTable::class ||
            this == OrderedMapConfigTable::class ||
            this == ListConfigTable::class ||
            this == SingleConfigTable::class
}

private fun Map<KClass<out ConfigTable<*>>, ConfigTable<*>>.validateTableTypes(): Map<KClass<out ConfigTable<*>>, ConfigTable<*>> {
    for ((type, table) in this) {
        require(type.isInstance(table)) {
            "config table ${table.name} is not an instance of ${type.qualifiedName}"
        }
    }
    return this
}
