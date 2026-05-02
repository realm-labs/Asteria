package io.github.mikai233.asteria.config

import io.github.mikai233.asteria.core.AsteriaDsl
import io.github.mikai233.asteria.core.AsteriaModule
import io.github.mikai233.asteria.core.ModuleContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Application module that registers [ConfigService].
 *
 * Configure it with a [ConfigLoader], optional runtime components, and validators. By default the first snapshot is
 * loaded during application start.
 */
class ConfigModule private constructor(
    private val options: ConfigModuleOptions,
) : AsteriaModule {
    override val name: String = "config"

    override suspend fun install(context: ModuleContext) {
        val loader = options.loader ?: error("config loader must be configured")
        val monitor = ConfigReloadMonitor(options.reloadHistorySize)
        val service = ConfigService(
            loader = loader,
            validators = options.validators,
            componentBuilders = options.componentBuilders,
        )
        service.subscribe(monitor)
        options.reloadListeners.forEach { listener ->
            service.subscribe(listener)
        }
        context.services.register(ConfigService::class, service)
        context.services.register(ConfigReloadMonitor::class, monitor)

        options.hotReload?.let { hotReload ->
            val monitored = hotReload.copy(failureListeners = listOf(monitor) + hotReload.failureListeners)
            context.services.register(ConfigHotReloadService::class, ConfigHotReloadService(service, monitored))
        }
    }

    override suspend fun start(context: ModuleContext) {
        if (options.loadOnStart) {
            context.services.get(ConfigService::class).load()
        }
        context.services.find(ConfigHotReloadService::class)?.start()
    }

    override suspend fun stop(context: ModuleContext) {
        context.services.find(ConfigHotReloadService::class)?.stop()
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
    val componentBuilders: List<ConfigComponentBuilder<*>>,
    val reloadListeners: List<ConfigReloadListener>,
    val loadOnStart: Boolean,
    val hotReload: ConfigHotReloadOptions?,
    val reloadHistorySize: Int,
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

    /**
     * Number of recent reload records kept in memory for diagnostics.
     */
    var reloadHistorySize: Int = 50

    private var loader: ConfigLoader? = null
    private var hotReload: ConfigHotReloadOptions? = null
    private val validators: MutableList<ConfigValidator> = mutableListOf()
    private val componentBuilders: MutableList<ConfigComponentBuilder<*>> = mutableListOf()
    private val reloadListeners: MutableList<ConfigReloadListener> = mutableListOf()

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

    /**
     * Adds a runtime component builder.
     *
     * Builders run after raw tables are loaded and before a snapshot is validated and published.
     */
    fun component(builder: ConfigComponentBuilder<*>) {
        componentBuilders += builder
    }

    /**
     * Adds an inline runtime component builder.
     */
    inline fun <reified T : Any> component(
        name: String,
        dependencies: Set<ConfigTableName> = emptySet(),
        noinline build: suspend (ConfigSnapshot) -> T,
    ) {
        component(configComponentBuilder(name, dependencies, build))
    }

    /**
     * Adds a listener for successful initial loads and reloads.
     */
    fun onReload(listener: ConfigReloadListener) {
        reloadListeners += listener
    }

    /**
     * Adds an inline listener for successful initial loads and reloads.
     */
    fun onReload(listener: suspend (ConfigReloadResult) -> Unit) {
        reloadListeners += ConfigReloadListener(listener)
    }

    /**
     * Enables background hot reload from an external trigger.
     */
    fun hotReload(configure: ConfigHotReloadBuilder.() -> Unit) {
        hotReload = ConfigHotReloadBuilder().apply(configure).build()
    }

    internal fun build(): ConfigModuleOptions {
        return ConfigModuleOptions(
            loader = loader,
            validators = validators.toList(),
            componentBuilders = componentBuilders.toList(),
            reloadListeners = reloadListeners.toList(),
            loadOnStart = loadOnStart,
            hotReload = hotReload,
            reloadHistorySize = reloadHistorySize,
        )
    }
}

/**
 * Builder for config hot reload.
 */
@AsteriaDsl
class ConfigHotReloadBuilder {
    /**
     * Delay used to merge bursts of config-center or filesystem events into one reload.
     */
    var debounce: Duration = 2.seconds

    /**
     * Delay before re-subscribing when the trigger stream fails.
     */
    var retryDelay: Duration = 5.seconds

    private var trigger: ConfigReloadTrigger? = null
    private val failureListeners: MutableList<ConfigReloadFailureListener> = mutableListOf()

    /**
     * Sets the signal source that drives hot reload.
     */
    fun trigger(trigger: ConfigReloadTrigger) {
        this.trigger = trigger
    }

    /**
     * Adds a listener for reload and watch failures.
     */
    fun onFailure(listener: ConfigReloadFailureListener) {
        failureListeners += listener
    }

    /**
     * Adds an inline listener for reload and watch failures.
     */
    fun onFailure(listener: suspend (ConfigReloadFailed) -> Unit) {
        failureListeners += ConfigReloadFailureListener(listener)
    }

    internal fun build(): ConfigHotReloadOptions {
        return ConfigHotReloadOptions(
            trigger = trigger ?: error("config hot reload trigger must be configured"),
            debounce = debounce,
            retryDelay = retryDelay,
            failureListeners = failureListeners.toList(),
        )
    }
}
