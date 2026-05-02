package io.github.mikai233.asteria.config

import io.github.mikai233.asteria.observability.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Loads a complete immutable config snapshot.
 *
 * Implementations should either return a fully valid snapshot or throw; partial data should never
 * be published through [ConfigService].
 */
fun interface ConfigLoader {
    suspend fun load(): ConfigSnapshot
}

/**
 * Listener notified after a new snapshot has been validated and published.
 */
fun interface ConfigReloadListener {
    suspend fun reloaded(result: ConfigReloadResult)
}

/**
 * Handle returned by [ConfigService.subscribe].
 */
interface ConfigReloadSubscription {
    /**
     * Removes the associated reload listener.
     */
    fun close()
}

/**
 * Result of a load or reload operation.
 */
data class ConfigReloadResult(
    val previous: ConfigSnapshot?,
    val current: ConfigSnapshot,
    val changedTables: Set<ConfigTableName> = emptySet(),
) {
    /**
     * Returns the business-facing change event for a real reload, or `null` for the initial load
     * and no-op reloads.
     */
    fun changeEventOrNull(): ConfigChangedEvent? {
        val previous = previous ?: return null
        if (changedTables.isEmpty()) {
            return null
        }
        return ConfigChangedEvent(
            previousRevision = previous.revision,
            currentRevision = current.revision,
            current = current,
            changedTables = changedTables,
        )
    }
}

/**
 * Owns the current config snapshot and reload lifecycle.
 *
 * Reload is serialized. A newly loaded snapshot is validated before replacing the current snapshot,
 * so readers either see the previous valid snapshot or the next valid snapshot.
 */
class ConfigService(
    private val loader: ConfigLoader,
    private val validators: List<ConfigValidator> = emptyList(),
    private val componentBuilders: List<ConfigComponentBuilder<*>> = emptyList(),
    private val tracer: Tracer = NoopTracer,
    private val metrics: Metrics = NoopMetrics,
) {
    private val logger = LoggerFactory.getLogger(ConfigService::class.java)
    private val listeners: CopyOnWriteArrayList<ConfigReloadListener> = CopyOnWriteArrayList()
    private val reloadLock: Mutex = Mutex()

    @Volatile
    private var snapshot: ConfigSnapshot? = null

    /**
     * Returns the current snapshot.
     *
     * Throws if [load] or [reload] has not completed successfully yet.
     */
    fun current(): ConfigSnapshot {
        return snapshot ?: error("config snapshot has not been loaded")
    }

    /**
     * Returns the current snapshot, or `null` before the first successful load.
     */
    fun currentOrNull(): ConfigSnapshot? {
        return snapshot
    }

    /**
     * Performs the initial load.
     *
     * This is equivalent to [reload] and exists to make startup code read naturally.
     */
    suspend fun load(): ConfigReloadResult {
        return reload()
    }

    /**
     * Loads, validates, publishes, and broadcasts a new snapshot.
     */
    suspend fun reload(): ConfigReloadResult {
        return tracer.span("config.reload") {
            metrics.counter("asteria.config.reload.total").increment()
            metrics.timer("asteria.config.reload.duration").record {
                reloadLock.withLock {
                    try {
                        val raw = loader.load()
                        val loaded = buildComponents(raw)
                        validate(loaded)

                        val previous = snapshot
                        val changedTables = ConfigSnapshotDiff.between(previous, loaded).changedTableNames()
                        val result = ConfigReloadResult(
                            previous = previous,
                            current = loaded,
                            changedTables = changedTables,
                        )
                        snapshot = loaded

                        val tags = result.metricTags()
                        metrics.counter("asteria.config.reload.published.total", tags).increment()
                        event("config.reload.published", result.traceAttributes())
                        logger.info(
                            "config reload published revision={} changedTables={}",
                            loaded.revision.version,
                            changedTables.size,
                        )

                        for (listener in listeners) {
                            listener.reloaded(result)
                        }

                        result
                    } catch (error: Throwable) {
                        metrics.counter("asteria.config.reload.failed.total").increment()
                        this@span.error(error)
                        logger.error("config reload failed", error)
                        throw error
                    }
                }
            }
        }
    }

    private suspend fun buildComponents(snapshot: ConfigSnapshot): ConfigSnapshot {
        if (componentBuilders.isEmpty()) {
            return snapshot
        }
        val components = componentBuilders.map { builder ->
            BuiltConfigComponent(builder.type, builder.build(snapshot))
        }
        return RuntimeComponentConfigSnapshot(snapshot, components)
    }

    /**
     * Subscribes to successful reloads.
     */
    fun subscribe(listener: ConfigReloadListener): ConfigReloadSubscription {
        listeners += listener
        return object : ConfigReloadSubscription {
            override fun close() {
                listeners -= listener
            }
        }
    }

    private suspend fun validate(snapshot: ConfigSnapshot) {
        val errors = validators.flatMap { it.validate(snapshot).errors }
        ConfigValidationResult(errors).throwIfFailed()
    }
}

private fun ConfigReloadResult.metricTags(): MetricTags {
    return MetricTags.of(
        "initial" to (previous == null).toString(),
        "changed" to changedTables.isNotEmpty().toString(),
    )
}

private fun ConfigReloadResult.traceAttributes(): TraceAttributes {
    return TraceAttributes.of(
        "config.revision" to current.revision.version,
        "config.changed_tables" to changedTables.size.toString(),
        "config.initial" to (previous == null).toString(),
    )
}

private fun ConfigSnapshotDiff.changedTableNames(): Set<ConfigTableName> {
    return (addedTables + removedTables + changedTables).mapTo(linkedSetOf()) { it.name }
}

private data class BuiltConfigComponent(
    val type: kotlin.reflect.KClass<*>,
    val value: Any,
)

private class RuntimeComponentConfigSnapshot(
    private val base: ConfigSnapshot,
    components: List<BuiltConfigComponent>,
) : ConfigSnapshot {
    private val componentsByType: Map<kotlin.reflect.KClass<*>, Any> = components.associateUniqueBy(
        keyOf = { it.type },
        errorOf = { "duplicate config component ${it.type.qualifiedName}" },
    ).mapValues { it.value.value }

    init {
        for (type in componentsByType.keys) {
            require(base.component(type) == null) {
                "duplicate config component ${type.qualifiedName}"
            }
        }
    }

    override val revision: ConfigRevision
        get() = base.revision

    override fun table(name: ConfigTableName): ConfigTable<*, *>? {
        return base.table(name)
    }

    override fun tables(): Collection<ConfigTable<*, *>> {
        return base.tables()
    }

    override fun <T : Any> component(type: kotlin.reflect.KClass<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return componentsByType[type] as T? ?: base.component(type)
    }

    override fun components(): Collection<Any> {
        return base.components() + componentsByType.values
    }
}

private fun <K : Any, V : Any> Iterable<V>.associateUniqueBy(
    keyOf: (V) -> K,
    errorOf: (V) -> String,
): Map<K, V> {
    val result = linkedMapOf<K, V>()
    for (value in this) {
        val key = keyOf(value)
        require(!result.containsKey(key)) { errorOf(value) }
        result[key] = value
    }
    return result
}
