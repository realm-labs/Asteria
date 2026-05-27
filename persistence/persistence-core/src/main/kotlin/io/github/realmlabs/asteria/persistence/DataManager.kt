package io.github.realmlabs.asteria.persistence

import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass
import kotlin.time.Clock

/**
 * Actor-local manager for one entity's mutable data modules.
 *
 * The manager assumes serialized access from the owning actor or an equivalent single-threaded boundary. A typical
 * lifecycle is `start`, then `getOrLoad` or `use` during message handling, followed by periodic `tick`/`flush` and
 * `unloadIdle`. Data in [DataBucket.unloadableLazy] buckets must be accessed through [use] so the manager can keep
 * the lease valid only while the caller is inside the block.
 */
class DataManager<ID : Any>(
    private val scope: DataScope<ID>,
    modules: Iterable<DataModule<ID, out MemData, out DataBucketPolicy>>,
    private val clock: Clock = Clock.System,
    private val metrics: Metrics = NoopMetrics,
) {
    private val logger = LoggerFactory.getLogger(DataManager::class.java)
    private val modules: List<DataModule<ID, out MemData, out DataBucketPolicy>> = modules.toList()
    private val modulesByType: Map<KClass<out MemData>, DataModule<ID, out MemData, out DataBucketPolicy>> =
        this.modules.associateBy { it.type }.also { modulesByType ->
            check(modulesByType.size == this.modules.size) { "data modules contain duplicate data types" }
        }
    private val loadedDataByType: MutableMap<KClass<out MemData>, LoadedData<ID, out MemData>> = linkedMapOf()
    private var lifecycle: DataManagerLifecycle = DataManagerLifecycle.Created

    /**
     * Starts this manager and loads modules declared with [DataBucket.eager].
     */
    suspend fun start() = measured("start", baseTags()) {
        check(lifecycle == DataManagerLifecycle.Created) {
            "data manager for ${scope.entityKind}:${scope.entityId} already started"
        }
        modulesByType.values
            .filter { it.bucket is EagerDataBucket }
            .forEach { load(it) }
        lifecycle = DataManagerLifecycle.Active
    }

    /**
     * Returns loaded data or lazily loads it.
     *
     * Only [ResidentMemData] is accepted because the returned reference can outlive the call. Use [use] for unloadable
     * data.
     */
    suspend fun <T : ResidentMemData> getOrLoad(type: KClass<T>): T {
        requireActive()
        val module = residentModule(type)
        loaded(type)?.let { data ->
            metrics.counter("asteria.persistence.data.cache.hit.total", dataTags(module)).increment()
            return data
        }
        metrics.counter("asteria.persistence.data.cache.miss.total", dataTags(module)).increment()
        return load(module)
    }

    /**
     * Runs [block] with data loaded and marks it as recently accessed.
     */
    suspend fun <T : MemData, R> use(type: KClass<T>, block: suspend (T) -> R): R {
        requireActive()
        val module = module(type)
        return measured("use", dataTags(module)) {
            val data = getOrLoadForUse(module)
            touch(type)
            try {
                block(data)
            } finally {
                touch(type)
            }
        }
    }

    /**
     * Runs [block] with multiple distinct data units loaded under the same access window.
     *
     * This overload has the same lease and idle-touch semantics as single-type [use]. Duplicate types are rejected
     * because they would make block argument identity ambiguous.
     */
    suspend fun <A : MemData, B : MemData, R> use(
        firstType: KClass<A>,
        secondType: KClass<B>,
        block: suspend (A, B) -> R,
    ): R {
        return useMany(listOf(firstType, secondType)) { data ->
            @Suppress("UNCHECKED_CAST")
            block(data[0] as A, data[1] as B)
        }
    }

    suspend fun <A : MemData, B : MemData, C : MemData, R> use(
        firstType: KClass<A>,
        secondType: KClass<B>,
        thirdType: KClass<C>,
        block: suspend (A, B, C) -> R,
    ): R {
        return useMany(listOf(firstType, secondType, thirdType)) { data ->
            @Suppress("UNCHECKED_CAST")
            block(data[0] as A, data[1] as B, data[2] as C)
        }
    }

    suspend fun <A : MemData, B : MemData, C : MemData, D : MemData, R> use(
        firstType: KClass<A>,
        secondType: KClass<B>,
        thirdType: KClass<C>,
        fourthType: KClass<D>,
        block: suspend (A, B, C, D) -> R,
    ): R {
        return useMany(listOf(firstType, secondType, thirdType, fourthType)) { data ->
            @Suppress("UNCHECKED_CAST")
            block(data[0] as A, data[1] as B, data[2] as C, data[3] as D)
        }
    }

    suspend fun <A : MemData, B : MemData, C : MemData, D : MemData, E : MemData, R> use(
        firstType: KClass<A>,
        secondType: KClass<B>,
        thirdType: KClass<C>,
        fourthType: KClass<D>,
        fifthType: KClass<E>,
        block: suspend (A, B, C, D, E) -> R,
    ): R {
        return useMany(listOf(firstType, secondType, thirdType, fourthType, fifthType)) { data ->
            @Suppress("UNCHECKED_CAST")
            block(data[0] as A, data[1] as B, data[2] as C, data[3] as D, data[4] as E)
        }
    }

    /**
     * Returns a data unit that must already be loaded.
     *
     * This is intended for code paths that are already inside a controlled lifecycle phase. It does not load lazy data
     * and does not refresh idle timestamps.
     */
    fun <T : MemData> requireLoaded(type: KClass<T>): T {
        requireActive()
        @Suppress("UNCHECKED_CAST")
        return loadedDataByType[type]?.data as? T ?: error("mem data ${type.qualifiedName} not loaded")
    }

    suspend fun tick() = measured("tick", baseTags()) {
        requireActive()
        loadedDataByType.values.toList().forEach { loadedData ->
            val data = loadedData.data
            if (data is AutoFlushMemData) {
                measured("data_tick", dataTags(loadedData.module)) {
                    data.tick()
                }
            }
        }
        unloadIdle()
    }

    /**
     * Flushes currently loaded auto-flush data once.
     *
     * The manager keeps iterating after failures and returns false if any data unit reports failure. Exceptions are
     * recorded and rethrown by the manager's operation wrapper.
     */
    suspend fun flush(): Boolean = measured("flush", baseTags()) {
        requireActive()
        var success = true
        loadedDataByType.values
            .map { it.data }
            .filterIsInstance<AutoFlushMemData>()
            .forEach { data ->
                success = data.flush() && success
            }
        success
    }

    /**
     * Requests a clean state from all currently loaded auto-flush data.
     *
     * [drain] is stronger than ordinary [flush] for implementations that distinguish bounded tick work from full
     * unload/shutdown work. The manager returns false if any data unit cannot become clean.
     */
    suspend fun drain(): Boolean = measured("drain", baseTags()) {
        requireActive()
        var success = true
        loadedDataByType.values
            .map { it.data }
            .filterIsInstance<AutoFlushMemData>()
            .forEach { data ->
                success = data.drain() && success
            }
        success
    }

    /**
     * Flushes and unloads idle data from unloadable buckets.
     */
    suspend fun unloadIdle() = measured("unload_idle", baseTags()) {
        requireActive()
        val now = clock.now().toEpochMilliseconds()
        val expired = loadedDataByType.values
            .filter { loadedData ->
                val bucket = loadedData.module.bucket as? UnloadableLazyDataBucket ?: return@filter false
                val idleUnloadAfter = bucket.idleUnloadAfter
                val idleMillis = idleUnloadAfter.inWholeMilliseconds
                now - loadedData.lastAccessMillis >= idleMillis
            }
            .toList()
        val failure = BatchFailure()
        expired.forEach { loadedData ->
            try {
                unload(loadedData)
            } catch (error: Throwable) {
                failure.record(error)
            }
        }
        failure.throwIfAny()
    }

    private fun <T : ResidentMemData> residentModule(type: KClass<T>): ResidentDataModule<ID, T> {
        @Suppress("UNCHECKED_CAST")
        return modulesByType[type] as? ResidentDataModule<ID, T>
            ?: error("resident data module ${type.qualifiedName} not registered")
    }

    private fun <T : MemData> module(type: KClass<T>): DataModule<ID, T, out DataBucketPolicy> {
        @Suppress("UNCHECKED_CAST")
        return modulesByType[type] as? DataModule<ID, T, out DataBucketPolicy>
            ?: error("data module ${type.qualifiedName} not registered")
    }

    private fun moduleUntyped(type: KClass<out MemData>): DataModule<ID, out MemData, out DataBucketPolicy> {
        return modulesByType[type] ?: error("data module ${type.qualifiedName} not registered")
    }

    private fun <T : MemData> loaded(type: KClass<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return loadedDataByType[type]?.data as? T
    }

    private suspend fun <T : MemData> getOrLoadForUse(module: DataModule<ID, T, out DataBucketPolicy>): T {
        loaded(module.type)?.let { data ->
            metrics.counter("asteria.persistence.data.cache.hit.total", dataTags(module)).increment()
            return data
        }
        metrics.counter("asteria.persistence.data.cache.miss.total", dataTags(module)).increment()
        return load(module)
    }

    private suspend fun <R> useMany(
        types: List<KClass<out MemData>>,
        block: suspend (List<MemData>) -> R,
    ): R {
        requireActive()
        checkDistinctUseTypes(types)
        val modules = types.map(::moduleUntyped)
        return measured("use_many", baseTags()) {
            val data = modules.map { module -> getOrLoadForUse(module) }
            types.forEach(::touch)
            try {
                block(data)
            } finally {
                types.forEach(::touch)
            }
        }
    }

    private suspend fun <T : MemData> load(module: DataModule<ID, T, out DataBucketPolicy>): T {
        loaded(module.type)?.let { return it }
        return measured("load", dataTags(module)) {
            val data = module.create(scope)
            val lease = module.bindLeaseIfNeeded(data)
            data.load()
            loadedDataByType[module.type] = LoadedData(module, data, clock.now().toEpochMilliseconds(), lease)
            data
        }
    }

    private fun touch(type: KClass<out MemData>) {
        val loadedData = loadedDataByType[type] ?: return
        loadedData.lease?.ensureActive()
        loadedData.lastAccessMillis = clock.now().toEpochMilliseconds()
    }

    private fun checkDistinctUseTypes(types: List<KClass<out MemData>>) {
        check(types.toSet().size == types.size) { "use requires distinct mem data types" }
    }

    private fun requireActive() {
        check(lifecycle == DataManagerLifecycle.Active) {
            "data manager for ${scope.entityKind}:${scope.entityId} is not started"
        }
    }

    private suspend fun unload(loadedData: LoadedData<ID, out MemData>) =
        measured("unload", dataTags(loadedData.module)) {
            val data = loadedData.data
            if (data is AutoFlushMemData && !data.drain()) {
                metrics.counter("asteria.persistence.data.unload.skipped.total", dataTags(loadedData.module))
                    .increment()
                loadedData.lastAccessMillis = clock.now().toEpochMilliseconds()
                return@measured
            }
            loadedData.lease?.invalidate()
            loadedDataByType.remove(loadedData.module.type)
        }

    private fun baseTags(): MetricTags {
        return MetricTags.of("entity_kind" to scope.entityKind.value)
    }

    private fun dataTags(module: DataModule<ID, out MemData, out DataBucketPolicy>): MetricTags {
        return MetricTags.of(
            "entity_kind" to scope.entityKind.value,
            "data" to (module.type.qualifiedName ?: module.type.toString()),
            "bucket" to module.bucket.name,
            "load_policy" to module.bucket.metricName,
        )
    }

    private suspend fun <T> measured(operation: String, tags: MetricTags, block: suspend () -> T): T {
        val metricTags = tags + MetricTags.of("operation" to operation)
        metrics.counter("asteria.persistence.data.operation.total", metricTags).increment()
        val start = System.nanoTime()
        return try {
            block()
        } catch (error: Throwable) {
            metrics.counter("asteria.persistence.data.operation.failed.total", metricTags).increment()
            logger.warn("persistence data operation failed: operation={}", operation, error)
            throw error
        } finally {
            metrics.timer("asteria.persistence.data.operation.duration", metricTags)
                .record((System.nanoTime() - start) / 1_000_000)
        }
    }
}

private enum class DataManagerLifecycle {
    Created,
    Active,
}

private data class LoadedData<ID : Any, T : MemData>(
    val module: DataModule<ID, T, out DataBucketPolicy>,
    val data: T,
    var lastAccessMillis: Long,
    val lease: DataLease?,
)

suspend inline fun <reified T : ResidentMemData> DataManager<*>.getOrLoad(): T = getOrLoad(T::class)

suspend inline fun <reified T : MemData, R> DataManager<*>.use(noinline block: suspend (T) -> R): R {
    return use(T::class, block)
}

suspend inline fun <reified A : MemData, reified B : MemData, R> DataManager<*>.use(
    noinline block: suspend (A, B) -> R,
): R {
    return use(A::class, B::class, block)
}

suspend inline fun <reified A : MemData, reified B : MemData, reified C : MemData, R> DataManager<*>.use(
    noinline block: suspend (A, B, C) -> R,
): R {
    return use(A::class, B::class, C::class, block)
}

suspend inline fun <
        reified A : MemData,
        reified B : MemData,
        reified C : MemData,
        reified D : MemData,
        R,
        > DataManager<*>.use(
    noinline block: suspend (A, B, C, D) -> R,
): R {
    return use(A::class, B::class, C::class, D::class, block)
}

suspend inline fun <
        reified A : MemData,
        reified B : MemData,
        reified C : MemData,
        reified D : MemData,
        reified E : MemData,
        R,
        > DataManager<*>.use(
    noinline block: suspend (A, B, C, D, E) -> R,
): R {
    return use(A::class, B::class, C::class, D::class, E::class, block)
}

inline fun <reified T : MemData> DataManager<*>.requireLoaded(): T = requireLoaded(T::class)
