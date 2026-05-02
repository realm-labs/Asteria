package io.github.mikai233.asteria.persistence

import io.github.mikai233.asteria.core.EntityKind
import io.github.mikai233.asteria.core.ServiceRegistry
import kotlin.reflect.KClass

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
