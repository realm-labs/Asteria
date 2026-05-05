package io.github.realmlabs.asteria.config.center

import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Typed config value together with the backend revision that produced it.
 */
data class Versioned<T : Any>(
    val path: ConfigPath,
    val value: T,
    val revision: ConfigRevision,
)

/**
 * Typed watch event emitted by [RuntimeConfigRepository.watchValue].
 */
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

/**
 * Current direct-child view under a config directory.
 *
 * [values] are keyed by child name, not full path.
 */
data class RuntimeConfigChildrenSnapshot<T : Any>(
    val path: ConfigPath,
    val values: Map<String, Versioned<T>>,
)

/**
 * Typed facade over [ConfigStore].
 *
 * This repository owns serialization and watch self-healing:
 * - `get` and `children` decode raw bytes into typed values.
 * - `watchValue` rebuilds dead watches after failures and emits a synthetic resync cycle before forwarding new events.
 * - `watchChildren` re-reads the full child snapshot after every child event and after every internal watch rebuild.
 *
 * The retry loop keeps running until the collecting coroutine is cancelled. It does not cap retries or switch to a
 * circuit-breaker state on repeated backend failures.
 */
class RuntimeConfigRepository(
    private val store: ConfigStore,
    private val codec: ConfigCodec,
    private val metrics: Metrics = NoopMetrics,
    private val watchRetryDelay: Duration = 5.seconds,
) {
    private val logger = LoggerFactory.getLogger(RuntimeConfigRepository::class.java)

    /**
     * Reads and decodes the current value at [path].
     */
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

    /**
     * Reads and decodes the current direct children under [path].
     *
     * The returned map is keyed by child name. Any decode failure fails the whole call so callers never observe a
     * partially decoded snapshot.
     */
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

    /**
     * Watches a single value and emits typed edge-triggered events.
     *
     * This flow does not emit the current value when collection starts; read [get] first if an initial snapshot is
     * required. If the underlying watch fails or completes unexpectedly, the repository waits [watchRetryDelay], creates
     * a new watch, emits an internal resync marker, and then continues forwarding future events. The resync marker is
     * intentionally filtered out from the public flow because callers typically only care about concrete typed changes.
     */
    fun <T : Any> watchValue(
        path: ConfigPath,
        type: KClass<T>,
    ): Flow<RuntimeConfigEvent<T>> {
        return watch(path, ConfigWatchMode.Value).mapNotNull { event ->
            when (event) {
                is ConfigEvent.Resynced -> null
                is ConfigEvent.Upserted -> RuntimeConfigEvent.Upserted(path, event.entry.toVersioned(type))
                is ConfigEvent.Deleted -> RuntimeConfigEvent.Deleted(path)
            }
        }
    }

    inline fun <reified T : Any> watchValue(path: ConfigPath): Flow<RuntimeConfigEvent<T>> {
        return watchValue(path, T::class)
    }

    /**
     * Watches a directory-like path and emits complete typed child snapshots.
     *
     * When [emitInitial] is `true`, the first emission is the current result of [children]. After that, any child
     * change or internal watch resync causes the repository to re-read the whole child set and emit a fresh snapshot.
     * This means callers may receive duplicate snapshots when the backend only reported a reconnect or when a change
     * did not alter the decoded child map, but they never need to manually stitch edge events back into a full view.
     */
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

    /**
     * Encodes and writes a typed value to [path].
     */
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

    /**
     * Deletes a value from the underlying store.
     */
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
            var attempt = 0
            while (currentCoroutineContext().isActive) {
                metrics.counter("asteria.config.center.operation.total", tags).increment()
                val watch = try {
                    store.watch(path, mode)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    metrics.counter("asteria.config.center.operation.failed.total", tags).increment()
                    logger.error("config center watch create failed mode={} path={}", mode.name, path.value, error)
                    attempt++
                    delayRetry()
                    continue
                }

                try {
                    if (attempt > 0) {
                        emit(ConfigEvent.Resynced(path, mode))
                    }
                    watch.events.collect { event ->
                        metrics.counter("asteria.config.center.watch.event.total", MetricTags.of("mode" to mode.name))
                            .increment()
                        emit(event)
                    }
                    logger.warn("config center watch completed mode={} path={}; rebuilding", mode.name, path.value)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    metrics.counter("asteria.config.center.operation.failed.total", tags).increment()
                    logger.error("config center watch failed mode={} path={}; rebuilding", mode.name, path.value, error)
                } finally {
                    watch.close()
                }
                attempt++
                delayRetry()
            }
        }
    }

    private suspend fun delayRetry() {
        if (watchRetryDelay > Duration.ZERO) {
            delay(watchRetryDelay)
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
