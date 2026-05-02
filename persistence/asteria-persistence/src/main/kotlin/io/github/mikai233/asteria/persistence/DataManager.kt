package io.github.mikai233.asteria.persistence

import java.time.Clock
import kotlin.reflect.KClass

class DataManager<ID : Any>(
    private val scope: DataScope<ID>,
    modules: Iterable<DataModule<ID, out MemData>>,
    private val clock: Clock = Clock.systemUTC(),
) {
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
    suspend fun loadEager() {
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
        return loaded(type) ?: load(module)
    }

    /**
     * Runs [block] with data loaded and marks it as recently accessed.
     */
    suspend fun <T : MemData, R> use(type: KClass<T>, block: suspend (T) -> R): R {
        val module = module(type)
        val data = loaded(type) ?: load(module)
        touch(type)
        return try {
            block(data)
        } finally {
            touch(type)
        }
    }

    fun <T : MemData> requireLoaded(type: KClass<T>): T {
        @Suppress("UNCHECKED_CAST")
        return loadedDataByType[type]?.data as? T ?: error("mem data ${type.qualifiedName} not loaded")
    }

    suspend fun tick() {
        loadedDataByType.values.toList().forEach { loadedData ->
            val data = loadedData.data
            if (data is AutoFlushMemData) {
                data.tick()
            }
        }
        unloadIdle()
    }

    suspend fun flush(): Boolean {
        return loadedDataByType.values
            .map { it.data }
            .filterIsInstance<AutoFlushMemData>()
            .all { it.flush() }
    }

    /**
     * Flushes and unloads idle data from unloadable buckets.
     */
    suspend fun unloadIdle() {
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
        return data
    }

    private fun touch(type: KClass<out MemData>) {
        val loadedData = loadedDataByType[type] ?: return
        loadedData.lease?.ensureActive()
        loadedData.lastAccessMillis = clock.millis()
    }

    private suspend fun unload(loadedData: LoadedData<ID, out MemData>) {
        val data = loadedData.data
        if (data is AutoFlushMemData && !data.flush()) {
            loadedData.lastAccessMillis = clock.millis()
            return
        }
        loadedData.lease?.invalidate()
        loadedDataByType.remove(loadedData.module.type)
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
