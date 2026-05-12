package io.github.realmlabs.asteria.config.publisher

import io.github.realmlabs.asteria.config.ConfigLoader
import io.github.realmlabs.asteria.config.ConfigSnapshot
import io.github.realmlabs.asteria.config.center.ConfigCodec
import io.github.realmlabs.asteria.config.center.ConfigStore
import io.github.realmlabs.asteria.config.center.JacksonConfigCodec
import io.github.realmlabs.asteria.config.luban.LubanBinaryConfigLoader
import io.github.realmlabs.asteria.config.luban.LubanSnapshotBridge
import kotlin.reflect.KClass

/**
 * Loads the current published config revision from a config center and constructs Luban binary tables from memory.
 *
 * Runtime nodes should prefer this loader when config artifacts are published through [ConfigPublisher]. It validates
 * the manifest and raw artifacts before handing bytes to Luban, then keeps the resulting snapshot revision equal to the
 * published revision instead of recomputing a local loader revision.
 */
class ConfigPublicationLubanBinaryLoader<T : Any, L : Any>(
    private val tablesType: KClass<T>,
    private val consumer: ConfigPublicationConsumer,
    private val bridge: LubanSnapshotBridge<T, L>,
) : ConfigLoader {
    constructor(
        tablesType: KClass<T>,
        store: ConfigStore,
        bridge: LubanSnapshotBridge<T, L>,
        layout: ConfigPublicationLayout = ConfigPublicationLayout(),
        codec: ConfigCodec = JacksonConfigCodec(),
    ) : this(
        tablesType = tablesType,
        consumer = ConfigPublicationConsumer(store, layout, codec),
        bridge = bridge,
    )

    override suspend fun load(): ConfigSnapshot {
        val bundle = consumer.loadCurrent()
        return LubanBinaryConfigLoader(
            tablesType = tablesType,
            dataSource = bundle.lubanDataSource(),
            bridge = bridge,
            revisionFactory = { bundle.manifest.revision },
        ).load()
    }
}

/**
 * Reified factory for a Luban binary loader backed by the current published config revision.
 *
 * Use this in runtime module wiring when the Luban root tables type is available as a type argument and artifacts are
 * read from a [ConfigStore] instead of a local export directory.
 */
inline fun <reified T : Any> configPublicationLubanBinaryLoader(
    store: ConfigStore,
    bridge: LubanSnapshotBridge<T, *>,
    layout: ConfigPublicationLayout = ConfigPublicationLayout(),
    codec: ConfigCodec = JacksonConfigCodec(),
): ConfigPublicationLubanBinaryLoader<T, Any> {
    @Suppress("UNCHECKED_CAST")
    return ConfigPublicationLubanBinaryLoader(
        tablesType = T::class,
        store = store,
        bridge = bridge as LubanSnapshotBridge<T, Any>,
        layout = layout,
        codec = codec,
    )
}
