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
sealed class DataModule<ID : Any, T : MemData, P : DataBucketPolicy>(
    val type: KClass<T>,
    val bucket: DataBucket<P>,
) {

    /**
     * Constructs an actor-local data unit before [MemData.load] runs.
     *
     * Implementations should wire dependencies here and leave blocking storage reads to [MemData.load].
     */
    abstract fun create(scope: DataScope<ID>): T

    internal open fun bindLeaseIfNeeded(data: T): DataLease? = null
}

sealed class ResidentDataModule<ID : Any, T : ResidentMemData>(
    type: KClass<T>,
    bucket: ResidentDataBucket<ResidentDataBucketPolicy>,
) : DataModule<ID, T, ResidentDataBucketPolicy>(type, bucket)

sealed class UnloadableDataModule<ID : Any, T : UnloadableMemData>(
    type: KClass<T>,
    bucket: UnloadableLazyDataBucket,
) : DataModule<ID, T, UnloadableDataBucketPolicy>(type, bucket) {
    final override fun bindLeaseIfNeeded(data: T): DataLease {
        val lease = DataLease("mem data ${type.qualifiedName}")
        data.bindLease(lease)
        return lease
    }
}

inline fun <ID : Any, reified T : ResidentMemData> dataModule(
    bucket: ResidentDataBucket<ResidentDataBucketPolicy> = DataBucket.eager(),
    noinline create: (DataScope<ID>) -> T,
): ResidentDataModule<ID, T> {
    return DefaultResidentDataModule(T::class, bucket, create)
}

/**
 * Declares a module whose data may be unloaded while the owning actor is alive.
 */
inline fun <ID : Any, reified T : UnloadableMemData> unloadableDataModule(
    bucket: UnloadableLazyDataBucket,
    noinline create: (DataScope<ID>) -> T,
): UnloadableDataModule<ID, T> {
    return DefaultUnloadableDataModule(T::class, bucket, create)
}

@PublishedApi
internal class DefaultResidentDataModule<ID : Any, T : ResidentMemData>(
    type: KClass<T>,
    bucket: ResidentDataBucket<ResidentDataBucketPolicy>,
    private val factory: (DataScope<ID>) -> T,
) : ResidentDataModule<ID, T>(type, bucket) {
    override fun create(scope: DataScope<ID>): T = factory(scope)
}

@PublishedApi
internal class DefaultUnloadableDataModule<ID : Any, T : UnloadableMemData>(
    type: KClass<T>,
    bucket: UnloadableLazyDataBucket,
    private val factory: (DataScope<ID>) -> T,
) : UnloadableDataModule<ID, T>(type, bucket) {
    override fun create(scope: DataScope<ID>): T = factory(scope)
}
