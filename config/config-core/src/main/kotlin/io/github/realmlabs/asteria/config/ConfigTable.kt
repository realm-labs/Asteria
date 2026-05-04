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
 * [R] is the row object type. Loaders should build tables as immutable snapshots so readers can safely keep
 * references during a config reload.
 */
interface ConfigTable<R : Any> {
    /**
     * Table name used for lookup in a [ConfigSnapshot].
     */
    val name: ConfigTableName

    /**
     * Runtime row type used by typed lookup helpers to fail fast on mismatches.
     */
    val rowType: KClass<R>

    /**
     * Number of rows in this table.
     */
    val size: Int

    /**
     * Returns all rows in table iteration order.
     */
    fun all(): Collection<R>
}

/**
 * [ConfigTable] variant whose rows can be addressed by a typed key.
 */
interface KeyedConfigTable<K : Any, R : Any> : ConfigTable<R> {
    /**
     * Runtime key type used by typed lookup helpers to fail fast on mismatches.
     */
    val keyType: KClass<K>

    /**
     * All row keys in table iteration order when the implementation defines one.
     */
    val keys: Set<K>

    /**
     * Legacy alias for [keys].
     */
    val ids: Set<K>
        get() = keys

    /**
     * Returns a row by key, or `null` when the row is absent.
     */
    operator fun get(key: K): R?

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
 * [KeyedConfigTable] backed by an immutable copy of a [Map].
 */
class MapConfigTable<K : Any, R : Any>(
    override val name: ConfigTableName,
    override val keyType: KClass<K>,
    override val rowType: KClass<R>,
    rows: Map<K, R>,
) : KeyedConfigTable<K, R>, Map<K, R> {
    private val rows: Map<K, R> = rows.toMap()

    override val size: Int get() = rows.size
    override val entries: Set<Map.Entry<K, R>> get() = rows.entries
    override val keys: Set<K> get() = rows.keys
    override val values: Collection<R> get() = rows.values
    override val ids: Set<K> get() = rows.keys

    override fun containsKey(key: K): Boolean {
        return rows.containsKey(key)
    }

    override fun containsValue(value: R): Boolean {
        return rows.containsValue(value)
    }

    override fun get(key: K): R? {
        return rows[key]
    }

    override fun all(): Collection<R> {
        return rows.values
    }

    override fun isEmpty(): Boolean {
        return rows.isEmpty()
    }
}

/**
 * [KeyedConfigTable] backed by an immutable ordered copy of key-row pairs.
 *
 * [keys], [entries], [values], and [all] iterate in the order supplied to the constructor.
 */
class OrderedMapConfigTable<K : Any, R : Any>(
    override val name: ConfigTableName,
    override val keyType: KClass<K>,
    override val rowType: KClass<R>,
    rows: Iterable<Pair<K, R>>,
) : KeyedConfigTable<K, R>, Map<K, R> {
    private val rows: Map<K, R> = rows.toOrderedMap(name)

    override val size: Int get() = rows.size
    override val entries: Set<Map.Entry<K, R>> get() = rows.entries
    override val keys: Set<K> get() = rows.keys
    override val values: Collection<R> get() = rows.values

    override fun containsKey(key: K): Boolean {
        return rows.containsKey(key)
    }

    override fun containsValue(value: R): Boolean {
        return rows.containsValue(value)
    }

    override fun get(key: K): R? {
        return rows[key]
    }

    override fun all(): Collection<R> {
        return rows.values
    }

    override fun isEmpty(): Boolean {
        return rows.isEmpty()
    }
}

/**
 * [ConfigTable] backed by an immutable ordered copy of a [List].
 */
class ListConfigTable<R : Any> private constructor(
    override val name: ConfigTableName,
    override val rowType: KClass<R>,
    private val rows: List<R>,
) : ConfigTable<R>, List<R> by rows {
    constructor(
        name: ConfigTableName,
        rowType: KClass<R>,
        rows: Iterable<R>,
    ) : this(name, rowType, rows.toList())

    override fun all(): Collection<R> {
        return rows
    }
}

/**
 * [ConfigTable] containing exactly one row object.
 */
class SingleConfigTable<R : Any>(
    override val name: ConfigTableName,
    override val rowType: KClass<R>,
    val row: R,
) : ConfigTable<R> {
    override val size: Int = 1

    override fun all(): Collection<R> {
        return listOf(row)
    }

    fun get(): R {
        return row
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

/**
 * Creates an [OrderedMapConfigTable] with key and row types inferred from reified type arguments.
 */
inline fun <reified K : Any, reified R : Any> orderedMapConfigTable(
    name: String,
    rows: Iterable<Pair<K, R>>,
): OrderedMapConfigTable<K, R> {
    return OrderedMapConfigTable(ConfigTableName(name), K::class, R::class, rows)
}

/**
 * Creates an [OrderedMapConfigTable] from a generated [ConfigTableRef].
 */
fun <K : Any, R : Any> orderedMapConfigTable(
    ref: ConfigTableRef<K, R>,
    rows: Iterable<Pair<K, R>>,
): OrderedMapConfigTable<K, R> {
    return OrderedMapConfigTable(ref.name, ref.keyType, ref.rowType, rows)
}

/**
 * Creates a [ListConfigTable] with row type inferred from a reified type argument.
 */
inline fun <reified R : Any> listConfigTable(
    name: String,
    rows: Iterable<R>,
): ListConfigTable<R> {
    return ListConfigTable(ConfigTableName(name), R::class, rows)
}

/**
 * Creates a [ListConfigTable] from a row table ref.
 */
fun <R : Any> listConfigTable(
    ref: RowConfigTableRef<R>,
    rows: Iterable<R>,
): ListConfigTable<R> {
    return ListConfigTable(ref.name, ref.rowType, rows)
}

/**
 * Creates a [SingleConfigTable] with row type inferred from a reified type argument.
 */
inline fun <reified R : Any> singleConfigTable(
    name: String,
    row: R,
): SingleConfigTable<R> {
    return SingleConfigTable(ConfigTableName(name), R::class, row)
}

/**
 * Creates a [SingleConfigTable] from a row table ref.
 */
fun <R : Any> singleConfigTable(
    ref: RowConfigTableRef<R>,
    row: R,
): SingleConfigTable<R> {
    return SingleConfigTable(ref.name, ref.rowType, row)
}

private fun <K : Any, R : Any> Iterable<Pair<K, R>>.toOrderedMap(name: ConfigTableName): Map<K, R> {
    val result = linkedMapOf<K, R>()
    for ((key, row) in this) {
        require(!result.containsKey(key)) { "duplicate config row $key in table $name" }
        result[key] = row
    }
    return result
}
