package io.github.realmlabs.asteria.config.center

import io.github.realmlabs.asteria.core.AsteriaDsl
import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext
import io.github.realmlabs.asteria.observability.metricsOrNoop

/**
 * Registers a backend-neutral [ConfigStore], a [ConfigCodec], and the typed [RuntimeConfigRepository].
 *
 * This module is useful when the application already owns the concrete config-center client and only needs Asteria's
 * typed repository helpers on top.
 */
class ConfigCenterModule private constructor(
    private val options: ConfigCenterModuleOptions,
) : AsteriaModule {
    override val name: String = "config-center"

    override suspend fun install(context: ModuleContext) {
        val store = options.store ?: error("config store must be configured")
        context.services.register(ConfigStore::class, store)

        val codec = options.codec
        context.services.register(ConfigCodec::class, codec)
        context.services.register(
            RuntimeConfigRepository::class,
            RuntimeConfigRepository(store, codec, context.metricsOrNoop())
        )
    }

    companion object {
        operator fun invoke(configure: ConfigCenterModuleBuilder.() -> Unit = {}): ConfigCenterModule {
            return ConfigCenterModule(ConfigCenterModuleBuilder().apply(configure).build())
        }
    }
}

data class ConfigCenterModuleOptions(
    val store: ConfigStore?,
    val codec: ConfigCodec,
)

/**
 * Builder for [ConfigCenterModule].
 */
@AsteriaDsl
class ConfigCenterModuleBuilder {
    private var store: ConfigStore? = null
    private var codec: ConfigCodec = JacksonConfigCodec()

    /**
     * Supplies the low-level config store implementation.
     */
    fun store(store: ConfigStore) {
        this.store = store
    }

    /**
     * Supplies the typed payload codec used by [RuntimeConfigRepository].
     */
    fun codec(codec: ConfigCodec) {
        this.codec = codec
    }

    internal fun build(): ConfigCenterModuleOptions {
        return ConfigCenterModuleOptions(store, codec)
    }
}
