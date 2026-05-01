package io.github.mikai233.asteria.config

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface ConfigLoader {
    suspend fun load(): ConfigSnapshot
}

fun interface ConfigReloadListener {
    suspend fun reloaded(result: ConfigReloadResult)
}

interface ConfigReloadSubscription {
    fun close()
}

data class ConfigReloadResult(
    val previous: ConfigSnapshot?,
    val current: ConfigSnapshot,
)

class ConfigService(
    private val loader: ConfigLoader,
    private val validators: List<ConfigValidator> = emptyList(),
) {
    private val listeners: CopyOnWriteArrayList<ConfigReloadListener> = CopyOnWriteArrayList()
    private val reloadLock: Mutex = Mutex()

    @Volatile
    private var snapshot: ConfigSnapshot? = null

    fun current(): ConfigSnapshot {
        return snapshot ?: error("config snapshot has not been loaded")
    }

    fun currentOrNull(): ConfigSnapshot? {
        return snapshot
    }

    suspend fun load(): ConfigReloadResult {
        return reload()
    }

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
