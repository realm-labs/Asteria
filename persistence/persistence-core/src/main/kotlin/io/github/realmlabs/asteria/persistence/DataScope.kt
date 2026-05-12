package io.github.realmlabs.asteria.persistence

import io.github.realmlabs.asteria.core.EntityKind
import io.github.realmlabs.asteria.core.ServiceRegistry
import kotlin.reflect.KClass

/**
 * Construction context for data modules owned by one entity actor.
 *
 * [entityId] is the storage key for document-shaped data, and [services] is the lookup point for runtime dependencies
 * such as database clients, journals, metrics, or codecs. Module instances should keep this scope-local identity and
 * avoid sharing mutable state across actors.
 */
data class DataScope<ID : Any>(
    val entityKind: EntityKind,
    val entityId: ID,
    val services: ServiceRegistry,
)

/**
 * Factory for one actor-local data unit.
 */
interface DataModule<ID : Any, T : MemData> {
    val type: KClass<T>
    val bucket: DataBucket

    /**
     * Constructs an actor-local data unit before [MemData.load] runs.
     *
     * Implementations should wire dependencies here and leave blocking storage reads to [MemData.load].
     */
    fun create(scope: DataScope<ID>): T
}

inline fun <ID : Any, reified T : MemData> dataModule(
    bucket: DataBucket = DataBucket.eager(),
    noinline create: (DataScope<ID>) -> T,
): DataModule<ID, T> {
    return object : DataModule<ID, T> {
        override val type: KClass<T> = T::class
        override val bucket: DataBucket = bucket

        override fun create(scope: DataScope<ID>): T = create(scope)
    }
}
