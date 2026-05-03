package io.github.realmlabs.asteria.config.publisher

import io.github.realmlabs.asteria.config.ConfigReloadSignal
import io.github.realmlabs.asteria.config.ConfigReloadTrigger
import io.github.realmlabs.asteria.config.center.ConfigCenterReloadTrigger
import io.github.realmlabs.asteria.config.center.ConfigStore
import io.github.realmlabs.asteria.config.center.ConfigWatchMode
import kotlinx.coroutines.flow.Flow

/**
 * Watches only the publication current pointer.
 *
 * A config publication writes artifacts and the manifest before moving the current pointer. Reloading on any earlier
 * artifact write can observe an incomplete revision, so runtime nodes should trigger reloads from [layout.currentPath].
 */
class ConfigPublicationReloadTrigger(
    store: ConfigStore,
    layout: ConfigPublicationLayout = ConfigPublicationLayout(),
) : ConfigReloadTrigger {
    private val delegate = ConfigCenterReloadTrigger(
        store = store,
        path = layout.currentPath,
        mode = ConfigWatchMode.Value,
    )

    override fun events(): Flow<ConfigReloadSignal> {
        return delegate.events()
    }
}

fun configPublicationReloadTrigger(
    store: ConfigStore,
    layout: ConfigPublicationLayout = ConfigPublicationLayout(),
): ConfigPublicationReloadTrigger {
    return ConfigPublicationReloadTrigger(store, layout)
}
