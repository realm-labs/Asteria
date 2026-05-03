package io.github.realmlabs.asteria.config

import kotlin.reflect.KClass

/**
 * Strongly typed reference to a config table.
 *
 * This type is intentionally small so config exporters or code generators can emit one stable
 * reference per table:
 *
 * ```kotlin
 * object GameConfigTables {
 *     val Items = configTableRef<Int, ItemConfig>("items")
 * }
 * ```
 *
 * Business code can then use `snapshot[GameConfigTables.Items]` without manually constructing a
 * [ConfigTableName] or repeating the table key and row types at every call site.
 */
interface ConfigTableRef<K : Any, R : Any> {
    /**
     * Stable table name used in the loaded [ConfigSnapshot].
     */
    val name: ConfigTableName

    /**
     * Expected row id type.
     */
    val keyType: KClass<K>

    /**
     * Expected row object type.
     */
    val rowType: KClass<R>
}

/**
 * Default immutable [ConfigTableRef] implementation used by generated helpers and tests.
 */
data class DefaultConfigTableRef<K : Any, R : Any>(
    override val name: ConfigTableName,
    override val keyType: KClass<K>,
    override val rowType: KClass<R>,
) : ConfigTableRef<K, R>

/**
 * Creates a strongly typed table reference with key and row types inferred from reified arguments.
 */
inline fun <reified K : Any, reified R : Any> configTableRef(name: String): ConfigTableRef<K, R> {
    return DefaultConfigTableRef(ConfigTableName(name), K::class, R::class)
}

/**
 * Returns an untyped table by generated [ref] or throws with revision context.
 */
fun ConfigSnapshot.requireAnyTable(ref: ConfigTableRef<*, *>): ConfigTable<*, *> {
    return requireAnyTable(ref.name)
}

/**
 * Returns a typed table by generated [ref], or `null` when it is absent.
 *
 * The stored table must match the key and row types declared by [ref].
 */
fun <K : Any, R : Any> ConfigSnapshot.table(ref: ConfigTableRef<K, R>): ConfigTable<K, R>? {
    val table = table(ref.name) ?: return null
    require(table.keyType == ref.keyType) {
        "config table ${ref.name} key type mismatch, expected ${ref.keyType.qualifiedName}, " +
                "actual ${table.keyType.qualifiedName}"
    }
    require(table.rowType == ref.rowType) {
        "config table ${ref.name} row type mismatch, expected ${ref.rowType.qualifiedName}, " +
                "actual ${table.rowType.qualifiedName}"
    }
    @Suppress("UNCHECKED_CAST")
    return table as ConfigTable<K, R>
}

/**
 * Returns a typed table by generated [ref] or throws with revision context.
 */
fun <K : Any, R : Any> ConfigSnapshot.requireTable(ref: ConfigTableRef<K, R>): ConfigTable<K, R> {
    return table(ref) ?: error("config table ${ref.name} not found in revision ${revision.version}")
}

/**
 * Shortcut for [requireTable], intended for generated table refs.
 */
operator fun <K : Any, R : Any> ConfigSnapshot.get(ref: ConfigTableRef<K, R>): ConfigTable<K, R> {
    return requireTable(ref)
}
