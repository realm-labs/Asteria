package io.github.realmlabs.asteria.config.center

import io.github.realmlabs.asteria.config.ConfigReloadSignal
import io.github.realmlabs.asteria.config.ConfigReloadTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Adapts [ConfigStore.watch] events into config hot-reload signals.
 *
 * The trigger does not decode or publish config data. It only notifies [io.github.realmlabs.asteria.config.ConfigService]
 * that a complete snapshot should be reloaded by the configured loader.
 */
class ConfigCenterReloadTrigger(
    private val store: ConfigStore,
    private val path: ConfigPath,
    private val mode: ConfigWatchMode = ConfigWatchMode.Tree,
    private val retryDelay: Duration = 5.seconds,
) : ConfigReloadTrigger {
    private val logger = LoggerFactory.getLogger(ConfigCenterReloadTrigger::class.java)

    override fun events(): Flow<ConfigReloadSignal> {
        return flow {
            var attempt = 0
            while (currentCoroutineContext().isActive) {
                val watch = try {
                    store.watch(path, mode)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logger.error("config center reload watch create failed mode={} path={}", mode.name, path.value, error)
                    attempt++
                    delayRetry()
                    continue
                }

                try {
                    if (attempt > 0) {
                        emit(ConfigEvent.Resynced(path, mode).toSignal())
                    }
                    watch.events.collect { event ->
                        emit(event.toSignal())
                    }
                    logger.warn("config center reload watch completed mode={} path={}; rebuilding", mode.name, path.value)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logger.error("config center reload watch failed mode={} path={}; rebuilding", mode.name, path.value, error)
                } finally {
                    watch.close()
                }
                attempt++
                delayRetry()
            }
        }
    }

    private fun ConfigEvent.toSignal(): ConfigReloadSignal {
        return ConfigReloadSignal(
            reason = reason,
            source = path.value,
        )
    }

    private suspend fun delayRetry() {
        if (retryDelay > Duration.ZERO) {
            delay(retryDelay)
        }
    }

    private val ConfigEvent.reason: String
        get() = when (this) {
            is ConfigEvent.Resynced -> "config_center_resynced"
            is ConfigEvent.Upserted -> "config_center_upserted"
            is ConfigEvent.Deleted -> "config_center_deleted"
        }
}
