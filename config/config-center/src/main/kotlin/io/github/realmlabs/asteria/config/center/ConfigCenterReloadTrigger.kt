package io.github.realmlabs.asteria.config.center

import io.github.realmlabs.asteria.config.ConfigReloadSignal
import io.github.realmlabs.asteria.config.ConfigReloadTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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
) : ConfigReloadTrigger {
    override fun events(): Flow<ConfigReloadSignal> {
        return flow {
            val watch = store.watch(path, mode)
            try {
                watch.events.collect { event ->
                    emit(
                        ConfigReloadSignal(
                            reason = event.reason,
                            source = event.path.value,
                        ),
                    )
                }
            } finally {
                watch.close()
            }
        }
    }

    private val ConfigEvent.reason: String
        get() = when (this) {
            is ConfigEvent.Upserted -> "config_center_upserted"
            is ConfigEvent.Deleted -> "config_center_deleted"
        }
}
