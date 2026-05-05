package io.github.realmlabs.asteria.persistence

import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import org.slf4j.LoggerFactory
import java.time.Clock
import kotlin.reflect.KClass

/**
 * Actor-local manager for one entity's mutable data modules.
 *
 * The manager assumes serialized access from the owning actor or an equivalent single-threaded boundary. A typical
 * lifecycle is `loadEager`, then `getOrLoad` or `use` during message handling, followed by periodic `tick`/`flush` and
 * `unloadIdle`. Data in [DataLoadPolicy.UnloadableLazy] buckets must be accessed through [use] so the manager can keep
 * the lease valid only while the caller is inside the block.
 */
class DataManager<ID : Any>(
    private val scope: DataScope<ID>,
    modules: Iterable<DataModule<ID, out MemData>>,
    private val clock: Clock = Clock.systemUTC(),
    private val metrics: Metrics = NoopMetrics,
) {
    private val logger = LoggerFactory.getLogger(DataManager::class.java)
    private val modules: List<DataModule<ID, out MemData>> = modules.toList()
    private val modulesByType: Map<KClass<out MemData>, DataModule<ID, out MemData>> =
        this.modules.associateBy { it.type }.also { modulesByType ->
            check(modulesByType.size == this.modules.size) { "data modules contain duplicate data types" }
        }
    private val loadedDataByType: MutableMap<KClass<out MemData>, LoadedData<ID, out MemData>> = linkedMapOf()
    private var eagerLoaded: Boolean = false

    /**
     * Loads modules declared with [DataLoadPolicy.Eager].
     */
    suspend fun loadEager() = measured("load_eager", baseTags()) {
        check(!eagerLoaded) { "eager data for ${scope.entityKind}:${scope.entityId} already loaded" }
        eagerLoaded = true
        modulesByType.values
            .filter { it.bucket.loadPolicy == DataLoadPolicy.Eager }
            .forEach { load(it) }
    }

    /**
     * Returns loaded data or lazily loads it.
     *
     * Unloadable data is intentionally excluded from this API because the returned reference could outlive its lease.
     * Use [use] for unloadable data.
     */
    suspend fun <T : MemData> getOrLoad(type: KClass<T>): T {
        val module = module(type)
        check(module.bucket.loadPolicy != DataLoadPolicy.UnloadableLazy) {
            "unloadable mem data ${type.qualifiedName} must be accessed with use"
        }
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
        val module = module(type)
        return measured("use", dataTags(module)) {
            val data = loaded(type)?.also {
                metrics.counter("asteria.persistence.data.cache.hit.total", dataTags(module)).increment()
            } ?: run {
                metrics.counter("asteria.persistence.data.cache.miss.total", dataTags(module)).increment()
                load(module)
            }
            touch(type)
            try {
                block(data)
            } finally {
                touch(type)
            }
        }
    }

    fun <T : MemData> requireLoaded(type: KClass<T>): T {
        @Suppress("UNCHECKED_CAST")
        return loadedDataByType[type]?.data as? T ?: error("mem data ${type.qualifiedName} not loaded")
    }

    suspend fun tick() = measured("tick", baseTags()) {
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

    suspend fun flush(): Boolean = measured("flush", baseTags()) {
        loadedDataByType.values
            .map { it.data }
            .filterIsInstance<AutoFlushMemData>()
            .all { it.flush() }
    }

    /**
     * Flushes and unloads idle data from unloadable buckets.
     */
    suspend fun unloadIdle() = measured("unload_idle", baseTags()) {
        val now = clock.millis()
        val expired = loadedDataByType.values
            .filter { loadedData ->
                val idleUnloadAfter = loadedData.module.bucket.idleUnloadAfter ?: return@filter false
                val idleMillis = idleUnloadAfter.inWholeMilliseconds
                now - loadedData.lastAccessMillis >= idleMillis
            }
            .toList()
        expired.forEach { unload(it) }
    }

    private fun <T : MemData> module(type: KClass<T>): DataModule<ID, T> {
        @Suppress("UNCHECKED_CAST")
        return modulesByType[type] as? DataModule<ID, T>
            ?: error("data module ${type.qualifiedName} not registered")
    }

    private fun <T : MemData> loaded(type: KClass<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return loadedDataByType[type]?.data as? T
    }

    private suspend fun <T : MemData> load(module: DataModule<ID, T>): T {
        loaded(module.type)?.let { return it }
        return measured("load", dataTags(module)) {
            val data = module.create(scope)
            val lease = if (module.bucket.loadPolicy == DataLoadPolicy.UnloadableLazy) {
                val lease = DataLease("mem data ${module.type.qualifiedName}")
                require(data is DataLeaseAware) {
                    "unloadable mem data ${module.type.qualifiedName} must implement DataLeaseAware"
                }
                data.bindLease(lease)
                lease
            } else {
                null
            }
            data.load()
            loadedDataByType[module.type] = LoadedData(module, data, clock.millis(), lease)
            data
        }
    }

    private fun touch(type: KClass<out MemData>) {
        val loadedData = loadedDataByType[type] ?: return
        loadedData.lease?.ensureActive()
        loadedData.lastAccessMillis = clock.millis()
    }

    private suspend fun unload(loadedData: LoadedData<ID, out MemData>) =
        measured("unload", dataTags(loadedData.module)) {
            val data = loadedData.data
            if (data is AutoFlushMemData && !data.flush()) {
                metrics.counter("asteria.persistence.data.unload.skipped.total", dataTags(loadedData.module))
                    .increment()
                loadedData.lastAccessMillis = clock.millis()
                return@measured
            }
            loadedData.lease?.invalidate()
            loadedDataByType.remove(loadedData.module.type)
        }

    private fun baseTags(): MetricTags {
        return MetricTags.of("entity_kind" to scope.entityKind.value)
    }

    private fun dataTags(module: DataModule<ID, out MemData>): MetricTags {
        return MetricTags.of(
            "entity_kind" to scope.entityKind.value,
            "data" to (module.type.qualifiedName ?: module.type.toString()),
            "bucket" to module.bucket.name,
            "load_policy" to module.bucket.loadPolicy.name,
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

private data class LoadedData<ID : Any, T : MemData>(
    val module: DataModule<ID, T>,
    val data: T,
    var lastAccessMillis: Long,
    val lease: DataLease?,
)

suspend inline fun <reified T : MemData> DataManager<*>.getOrLoad(): T = getOrLoad(T::class)

suspend inline fun <reified T : MemData, R> DataManager<*>.use(noinline block: suspend (T) -> R): R {
    return use(T::class, block)
}

inline fun <reified T : MemData> DataManager<*>.requireLoaded(): T = requireLoaded(T::class)
