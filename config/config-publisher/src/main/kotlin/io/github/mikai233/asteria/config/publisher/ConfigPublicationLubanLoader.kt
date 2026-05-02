package io.github.mikai233.asteria.config.publisher

import io.github.mikai233.asteria.config.ConfigLoader
import io.github.mikai233.asteria.config.ConfigSnapshot
import io.github.mikai233.asteria.config.center.ConfigCodec
import io.github.mikai233.asteria.config.center.ConfigStore
import io.github.mikai233.asteria.config.center.JacksonConfigCodec
import io.github.mikai233.asteria.config.luban.LubanBinaryConfigLoader
import kotlin.reflect.KClass

/**
 * Loads the current published config revision from a config center and constructs Luban binary tables from memory.
 *
 * Runtime nodes should prefer this loader when config artifacts are published through [ConfigPublisher]. It validates
 * the manifest and raw artifacts before handing bytes to Luban, then keeps the resulting snapshot revision equal to the
 * published revision instead of recomputing a local loader revision.
 */
class ConfigPublicationLubanBinaryLoader(
    private val tablesType: KClass<out Any>,
    private val consumer: ConfigPublicationConsumer,
    private val includeTableComponents: Boolean = true,
) : ConfigLoader {
    constructor(
        tablesType: KClass<out Any>,
        store: ConfigStore,
        layout: ConfigPublicationLayout = ConfigPublicationLayout(),
        codec: ConfigCodec = JacksonConfigCodec(),
        includeTableComponents: Boolean = true,
    ) : this(
        tablesType = tablesType,
        consumer = ConfigPublicationConsumer(store, layout, codec),
        includeTableComponents = includeTableComponents,
    )

    override suspend fun load(): ConfigSnapshot {
        val bundle = consumer.loadCurrent()
        return LubanBinaryConfigLoader(
            tablesType = tablesType,
            dataSource = bundle.lubanDataSource(),
            includeTableComponents = includeTableComponents,
            revisionFactory = { bundle.manifest.revision },
        ).load()
    }
}

inline fun <reified T : Any> configPublicationLubanBinaryLoader(
    store: ConfigStore,
    layout: ConfigPublicationLayout = ConfigPublicationLayout(),
    codec: ConfigCodec = JacksonConfigCodec(),
    includeTableComponents: Boolean = true,
): ConfigPublicationLubanBinaryLoader {
    return ConfigPublicationLubanBinaryLoader(
        tablesType = T::class,
        store = store,
        layout = layout,
        codec = codec,
        includeTableComponents = includeTableComponents,
    )
}
