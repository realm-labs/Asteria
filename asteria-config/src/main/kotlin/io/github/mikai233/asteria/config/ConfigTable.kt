package io.github.mikai233.asteria.config

import kotlin.reflect.KClass

@JvmInline
value class ConfigTableName(val value: String) {
    init {
        require(value.isNotBlank()) { "config table name must not be blank" }
    }

    override fun toString(): String = value
}

interface ConfigTable<K : Any, R : Any> {
    val name: ConfigTableName
    val keyType: KClass<K>
    val rowType: KClass<R>
    val size: Int
    val ids: Set<K>

    operator fun get(id: K): R?

    fun all(): Collection<R>

    fun contains(id: K): Boolean {
        return get(id) != null
    }

    fun require(id: K): R {
        return get(id) ?: error("config row $id not found in table $name")
    }
}

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

inline fun <reified K : Any, reified R : Any> mapConfigTable(
    name: String,
    rows: Map<K, R>,
): MapConfigTable<K, R> {
    return MapConfigTable(ConfigTableName(name), K::class, R::class, rows)
}
