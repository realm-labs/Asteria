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
 * Strongly typed reference to any config table shape.
 */
interface RowConfigTableRef<R : Any> {
    /**
     * Stable table name used in the loaded [ConfigSnapshot].
     */
    val name: ConfigTableName

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
 * Default immutable [RowConfigTableRef] implementation used by generated helpers and tests.
 */
data class DefaultRowConfigTableRef<R : Any>(
    override val name: ConfigTableName,
    override val rowType: KClass<R>,
) : RowConfigTableRef<R>

/**
 * Creates a strongly typed table reference with key and row types inferred from reified arguments.
 */
inline fun <reified K : Any, reified R : Any> configTableRef(name: String): ConfigTableRef<K, R> {
    return DefaultConfigTableRef(ConfigTableName(name), K::class, R::class)
}

/**
 * Creates a strongly typed row table reference with row type inferred from a reified argument.
 */
inline fun <reified R : Any> rowConfigTableRef(name: String): RowConfigTableRef<R> {
    return DefaultRowConfigTableRef(ConfigTableName(name), R::class)
}

/**
 * Returns a typed table by generated [ref], or `null` when it is absent.
 *
 * The stored table must match the row type declared by [ref].
 */
fun <R : Any> ConfigSnapshot.table(ref: RowConfigTableRef<R>): ConfigTable<R>? {
    val table = table(ref.name) ?: return null
    require(table.rowType == ref.rowType) {
        "config table ${ref.name} row type mismatch, expected ${ref.rowType.qualifiedName}, " +
                "actual ${table.rowType.qualifiedName}"
    }
    @Suppress("UNCHECKED_CAST")
    return table as ConfigTable<R>
}

/**
 * Returns a typed table by generated [ref], or `null` when it is absent.
 *
 * The stored table must match the key and row types declared by [ref].
 */
fun <K : Any, R : Any> ConfigSnapshot.table(ref: ConfigTableRef<K, R>): KeyedConfigTable<K, R>? {
    val table = table(ref.name) ?: return null
    require(table is KeyedConfigTable<*, *>) {
        "config table ${ref.name} is not keyed"
    }
    require(table.keyType == ref.keyType) {
        "config table ${ref.name} key type mismatch, expected ${ref.keyType.qualifiedName}, " +
                "actual ${table.keyType.qualifiedName}"
    }
    require(table.rowType == ref.rowType) {
        "config table ${ref.name} row type mismatch, expected ${ref.rowType.qualifiedName}, " +
                "actual ${table.rowType.qualifiedName}"
    }
    @Suppress("UNCHECKED_CAST")
    return table as KeyedConfigTable<K, R>
}

/**
 * Returns a typed table by generated [ref] or throws with revision context.
 */
fun <R : Any> ConfigSnapshot.requireTable(ref: RowConfigTableRef<R>): ConfigTable<R> {
    return table(ref) ?: error("config table ${ref.name} not found in revision ${revision.version}")
}

/**
 * Returns a typed table by generated [ref] or throws with revision context.
 */
fun <K : Any, R : Any> ConfigSnapshot.requireTable(ref: ConfigTableRef<K, R>): KeyedConfigTable<K, R> {
    return table(ref) ?: error("config table ${ref.name} not found in revision ${revision.version}")
}

/**
 * Shortcut for [requireTable], intended for generated table refs.
 */
operator fun <R : Any> ConfigSnapshot.get(ref: RowConfigTableRef<R>): ConfigTable<R> {
    return requireTable(ref)
}

/**
 * Shortcut for [requireTable], intended for generated table refs.
 */
operator fun <K : Any, R : Any> ConfigSnapshot.get(ref: ConfigTableRef<K, R>): KeyedConfigTable<K, R> {
    return requireTable(ref)
}
