package io.github.mikai233.asteria.config.center

import io.github.mikai233.asteria.core.AsteriaDsl
import io.github.mikai233.asteria.core.AsteriaModule
import io.github.mikai233.asteria.core.ModuleContext

class ConfigCenterModule private constructor(
    private val options: ConfigCenterModuleOptions,
) : AsteriaModule {
    override val name: String = "config-center"

    override suspend fun install(context: ModuleContext) {
        val store = options.store ?: error("config store must be configured")
        context.services.register(ConfigStore::class, store)

        options.codec?.let { codec ->
            context.services.register(ConfigCodec::class, codec)
            context.services.register(RuntimeConfigRepository::class, RuntimeConfigRepository(store, codec))
        }
    }

    companion object {
        operator fun invoke(configure: ConfigCenterModuleBuilder.() -> Unit = {}): ConfigCenterModule {
            return ConfigCenterModule(ConfigCenterModuleBuilder().apply(configure).build())
        }
    }
}

data class ConfigCenterModuleOptions(
    val store: ConfigStore?,
    val codec: ConfigCodec?,
)

@AsteriaDsl
class ConfigCenterModuleBuilder {
    private var store: ConfigStore? = null
    private var codec: ConfigCodec? = null

    fun store(store: ConfigStore) {
        this.store = store
    }

    fun codec(codec: ConfigCodec) {
        this.codec = codec
    }

    internal fun build(): ConfigCenterModuleOptions {
        return ConfigCenterModuleOptions(store, codec)
    }
}
