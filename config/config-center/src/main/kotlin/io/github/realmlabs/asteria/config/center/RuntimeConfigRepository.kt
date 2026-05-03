package io.github.realmlabs.asteria.config.center

import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

data class Versioned<T : Any>(
    val path: ConfigPath,
    val value: T,
    val revision: ConfigRevision,
)

sealed interface RuntimeConfigEvent<out T : Any> {
    val path: ConfigPath

    data class Upserted<T : Any>(
        override val path: ConfigPath,
        val value: Versioned<T>,
    ) : RuntimeConfigEvent<T>

    data class Deleted(
        override val path: ConfigPath,
    ) : RuntimeConfigEvent<Nothing>
}

data class RuntimeConfigChildrenSnapshot<T : Any>(
    val path: ConfigPath,
    val values: Map<String, Versioned<T>>,
)

class RuntimeConfigRepository(
    private val store: ConfigStore,
    private val codec: ConfigCodec,
    private val metrics: Metrics = NoopMetrics,
) {
    private val logger = LoggerFactory.getLogger(RuntimeConfigRepository::class.java)

    suspend fun <T : Any> get(
        path: ConfigPath,
        type: KClass<T>,
    ): Versioned<T>? {
        return measured("get") {
            store.get(path)?.toVersioned(type)
        }
    }

    suspend inline fun <reified T : Any> get(path: ConfigPath): Versioned<T>? {
        return get(path, T::class)
    }

    suspend fun <T : Any> children(
        path: ConfigPath,
        type: KClass<T>,
    ): RuntimeConfigChildrenSnapshot<T> {
        return measured("children") {
            val values = store.children(path).associate { entry ->
                entry.path.name to entry.toVersioned(type)
            }
            RuntimeConfigChildrenSnapshot(path, values)
        }
    }

    suspend inline fun <reified T : Any> children(path: ConfigPath): RuntimeConfigChildrenSnapshot<T> {
        return children(path, T::class)
    }

    fun <T : Any> watchValue(
        path: ConfigPath,
        type: KClass<T>,
    ): Flow<RuntimeConfigEvent<T>> {
        return watch(path, ConfigWatchMode.Value).mapNotNull { event ->
            when (event) {
                is ConfigEvent.Upserted -> RuntimeConfigEvent.Upserted(path, event.entry.toVersioned(type))
                is ConfigEvent.Deleted -> RuntimeConfigEvent.Deleted(path)
            }
        }
    }

    inline fun <reified T : Any> watchValue(path: ConfigPath): Flow<RuntimeConfigEvent<T>> {
        return watchValue(path, T::class)
    }

    fun <T : Any> watchChildren(
        path: ConfigPath,
        type: KClass<T>,
        emitInitial: Boolean = true,
    ): Flow<RuntimeConfigChildrenSnapshot<T>> {
        return flow {
            if (emitInitial) {
                emit(children(path, type))
            }

            watch(path, ConfigWatchMode.Children).collect {
                emit(children(path, type))
            }
        }
    }

    inline fun <reified T : Any> watchChildren(
        path: ConfigPath,
        emitInitial: Boolean = true,
    ): Flow<RuntimeConfigChildrenSnapshot<T>> {
        return watchChildren(path, T::class, emitInitial)
    }

    suspend fun <T : Any> put(
        path: ConfigPath,
        value: T,
        type: KClass<T>,
        expectedRevision: ConfigRevision? = null,
    ): ConfigRevision {
        return measured("put") {
            store.put(path, codec.encode(value, type), expectedRevision)
        }
    }

    suspend inline fun <reified T : Any> put(
        path: ConfigPath,
        value: T,
        expectedRevision: ConfigRevision? = null,
    ): ConfigRevision {
        return put(path, value, T::class, expectedRevision)
    }

    suspend fun delete(
        path: ConfigPath,
        expectedRevision: ConfigRevision? = null,
    ) {
        measured("delete") {
            store.delete(path, expectedRevision)
        }
    }

    private fun watch(
        path: ConfigPath,
        mode: ConfigWatchMode,
    ): Flow<ConfigEvent> {
        return flow {
            val tags = MetricTags.of("operation" to "watch", "mode" to mode.name)
            metrics.counter("asteria.config.center.operation.total", tags).increment()
            val watch = store.watch(path, mode)
            try {
                watch.events.collect { event ->
                    metrics.counter("asteria.config.center.watch.event.total", MetricTags.of("mode" to mode.name))
                        .increment()
                    emit(event)
                }
            } catch (error: Throwable) {
                metrics.counter("asteria.config.center.operation.failed.total", tags).increment()
                logger.error("config center watch failed mode={}", mode.name, error)
                throw error
            } finally {
                watch.close()
            }
        }
    }

    private suspend fun <T> measured(operation: String, block: suspend () -> T): T {
        val tags = MetricTags.of("operation" to operation)
        val startedAt = System.nanoTime()
        metrics.counter("asteria.config.center.operation.total", tags).increment()
        try {
            return block()
        } catch (error: Throwable) {
            metrics.counter("asteria.config.center.operation.failed.total", tags).increment()
            logger.error("config center operation failed operation={}", operation, error)
            throw error
        } finally {
            metrics.timer("asteria.config.center.operation.duration", tags)
                .record((System.nanoTime() - startedAt) / 1_000_000)
        }
    }

    private fun <T : Any> ConfigEntry.toVersioned(type: KClass<T>): Versioned<T> {
        return Versioned(path, codec.decode(bytes, type), revision)
    }
}
