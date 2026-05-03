package io.github.realmlabs.asteria.config

import kotlin.reflect.KClass

/**
 * Stable name of a config table inside a [ConfigSnapshot].
 */
@JvmInline
value class ConfigTableName(val value: String) {
    init {
        require(value.isNotBlank()) { "config table name must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Immutable typed table of config rows.
 *
 * [K] is the row id type, and [R] is the row object type. Loaders should build tables as immutable
 * snapshots so readers can safely keep references during a config reload.
 */
interface ConfigTable<K : Any, R : Any> {
    /**
     * Table name used for lookup in a [ConfigSnapshot].
     */
    val name: ConfigTableName

    /**
     * Runtime key type used by typed lookup helpers to fail fast on mismatches.
     */
    val keyType: KClass<K>

    /**
     * Runtime row type used by typed lookup helpers to fail fast on mismatches.
     */
    val rowType: KClass<R>

    /**
     * Number of rows in this table.
     */
    val size: Int

    /**
     * All row ids.
     */
    val ids: Set<K>

    /**
     * Returns a row by id, or `null` when the row is absent.
     */
    operator fun get(id: K): R?

    /**
     * Returns all rows in table iteration order.
     */
    fun all(): Collection<R>

    /**
     * Returns whether the table contains [id].
     */
    fun contains(id: K): Boolean {
        return get(id) != null
    }

    /**
     * Returns a row by id or throws an error with table context.
     */
    fun require(id: K): R {
        return get(id) ?: error("config row $id not found in table $name")
    }
}

/**
 * [ConfigTable] backed by an immutable copy of a [Map].
 */
class MapConfigTable<K : Any, R : Any>(
    override val name: ConfigTableName,
    override val keyType: KClass<K>,
    override val rowType: KClass<R>,
    rows: Map<K, R>,
) : ConfigTable<K, R> {
    private val rows: Map<K, R> = rows.toMap()

    override val size: Int get() = rows.size
    override val ids: Set<K> get() = rows.keys

    override fun get(id: K): R? {
        return rows[id]
    }

    override fun all(): Collection<R> {
        return rows.values
    }
}

/**
 * Creates a [MapConfigTable] with key and row types inferred from reified type arguments.
 */
inline fun <reified K : Any, reified R : Any> mapConfigTable(
    name: String,
    rows: Map<K, R>,
): MapConfigTable<K, R> {
    return MapConfigTable(ConfigTableName(name), K::class, R::class, rows)
}

/**
 * Creates a [MapConfigTable] from a generated [ConfigTableRef].
 */
fun <K : Any, R : Any> mapConfigTable(
    ref: ConfigTableRef<K, R>,
    rows: Map<K, R>,
): MapConfigTable<K, R> {
    return MapConfigTable(ref.name, ref.keyType, ref.rowType, rows)
}
