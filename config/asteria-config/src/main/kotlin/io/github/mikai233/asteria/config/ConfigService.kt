package io.github.mikai233.asteria.config

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
)

/**
 * Owns the current config snapshot and reload lifecycle.
 *
 * Reload is serialized. A newly loaded snapshot is validated before replacing the current snapshot,
 * so readers either see the previous valid snapshot or the next valid snapshot.
 */
class ConfigService(
    private val loader: ConfigLoader,
    private val validators: List<ConfigValidator> = emptyList(),
) {
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
        return reloadLock.withLock {
            val loaded = loader.load()
            validate(loaded)

            val result = ConfigReloadResult(
                previous = snapshot,
                current = loaded,
            )
            snapshot = loaded

            for (listener in listeners) {
                listener.reloaded(result)
            }

            result
        }
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
