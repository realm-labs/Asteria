package io.github.mikai233.asteria.config

import io.github.mikai233.asteria.core.AsteriaDsl
import io.github.mikai233.asteria.core.AsteriaModule
import io.github.mikai233.asteria.core.ModuleContext

/**
 * Application module that registers [ConfigService].
 *
 * Configure it with a [ConfigLoader] and optional validators. By default the first snapshot is
 * loaded during application start.
 */
class ConfigModule private constructor(
    private val options: ConfigModuleOptions,
) : AsteriaModule {
    override val name: String = "config"

    override suspend fun install(context: ModuleContext) {
        val loader = options.loader ?: error("config loader must be configured")
        val service = ConfigService(
            loader = loader,
            validators = options.validators,
        )
        context.services.register(ConfigService::class, service)
    }

    override suspend fun start(context: ModuleContext) {
        if (options.loadOnStart) {
            context.services.get(ConfigService::class).load()
        }
    }

    companion object {
        operator fun invoke(configure: ConfigModuleBuilder.() -> Unit = {}): ConfigModule {
            return ConfigModule(ConfigModuleBuilder().apply(configure).build())
        }
    }
}

/**
 * Immutable options for [ConfigModule].
 */
data class ConfigModuleOptions(
    val loader: ConfigLoader?,
    val validators: List<ConfigValidator>,
    val loadOnStart: Boolean,
)

/**
 * Builder for [ConfigModule].
 */
@AsteriaDsl
class ConfigModuleBuilder {
    /**
     * Whether [ConfigService.load] should run during module start.
     */
    var loadOnStart: Boolean = true

    private var loader: ConfigLoader? = null
    private val validators: MutableList<ConfigValidator> = mutableListOf()

    /**
     * Sets the loader that produces full config snapshots.
     */
    fun loader(loader: ConfigLoader) {
        this.loader = loader
    }

    /**
     * Adds a reusable validator.
     */
    fun validator(validator: ConfigValidator) {
        validators += validator
    }

    /**
     * Adds an inline validator.
     */
    fun validator(validate: suspend ConfigValidationScope.(ConfigSnapshot) -> Unit) {
        validators += configValidator(validate)
    }

    internal fun build(): ConfigModuleOptions {
        return ConfigModuleOptions(
            loader = loader,
            validators = validators.toList(),
            loadOnStart = loadOnStart,
        )
    }
}
