package io.github.mikai233.asteria.config

import kotlin.reflect.KClass

data class ConfigRevision(
    val version: String,
    val checksum: String? = null,
) {
    init {
        require(version.isNotBlank()) { "config revision version must not be blank" }
        require(checksum == null || checksum.isNotBlank()) { "config revision checksum must not be blank" }
    }
}

interface ConfigSnapshot {
    val revision: ConfigRevision

    fun table(name: ConfigTableName): ConfigTable<*, *>?

    fun tables(): Collection<ConfigTable<*, *>>

    fun <T : Any> component(type: KClass<T>): T?

    fun components(): Collection<Any>
}

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

fun ConfigSnapshot.requireAnyTable(name: ConfigTableName): ConfigTable<*, *> {
    return table(name) ?: error("config table $name not found in revision ${revision.version}")
}

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

inline fun <reified K : Any, reified R : Any> ConfigSnapshot.requireTable(name: ConfigTableName): ConfigTable<K, R> {
    return table<K, R>(name) ?: error("config table $name not found in revision ${revision.version}")
}

inline fun <reified T : Any> ConfigSnapshot.component(): T? {
    return component(T::class)
}

inline fun <reified T : Any> ConfigSnapshot.requireComponent(): T {
    return component<T>() ?: error("config component ${T::class.qualifiedName} not found in revision ${revision.version}")
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
