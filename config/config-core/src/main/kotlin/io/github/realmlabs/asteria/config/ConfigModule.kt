package io.github.realmlabs.asteria.config

import io.github.realmlabs.asteria.core.AsteriaDsl
import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext
import io.github.realmlabs.asteria.observability.metricsOrNoop
import io.github.realmlabs.asteria.observability.tracerOrNoop
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Application module that registers [ConfigService].
 *
 * Configure it with a [ConfigLoader], optional runtime components, and validators. By default the first snapshot is
 * loaded during application start.
 *
 * When hot reload is enabled, this module also registers and starts [ConfigHotReloadService]. The hot reload loop is
 * stopped during module shutdown, but the last successfully published snapshot remains available until the application
 * tears its services down.
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
            validationParallelism = options.validationParallelism,
            tracer = context.tracerOrNoop(),
            metrics = context.metricsOrNoop(),
        )
        service.subscribe(monitor)
        options.reloadListeners.forEach { listener ->
            service.subscribe(listener)
        }
        context.services.register(ConfigService::class, service)
        context.services.register(ConfigReloadMonitor::class, monitor)

        options.hotReload?.let { hotReload ->
            val monitored = hotReload.copy(failureListeners = listOf(monitor) + hotReload.failureListeners)
            context.services.register(
                ConfigHotReloadService::class,
                ConfigHotReloadService(service, monitored, metrics = context.metricsOrNoop()),
            )
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
    val validationParallelism: Int,
)

/**
 * Describes one complete config lifecycle: where the snapshot comes from, how it is validated, what runtime components
 * are derived from it, and whether external triggers keep the snapshot fresh after startup.
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

    /**
     * Maximum number of validators allowed to run at the same time.
     */
    var validationParallelism: Int = 1

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
     * Adds reusable validators in registration order.
     */
    fun validators(validators: Iterable<ConfigValidator>) {
        this.validators += validators
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
     *
     * Listeners are not replayed for the current snapshot and run inline with the reload call.
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
     *
     * Trigger failures are handled by [ConfigHotReloadService]. A failed reload does not roll back to a previous signal;
     * the next attempt waits for a new trigger event or a trigger-generated resync event.
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
            validationParallelism = validationParallelism,
        )
    }
}

/**
 * Hot-reload wiring for [ConfigModule].
 *
 * The trigger stream is kept alive by the module service. Reload failures are reported to listeners and do not stop the
 * subscription loop.
 */
@AsteriaDsl
class ConfigHotReloadBuilder {
    /**
     * Delay used to merge bursts of config-center or filesystem events into one reload.
     */
    var debounce: Duration = 2.seconds

    /**
     * Delay before re-subscribing when the trigger stream fails or completes unexpectedly.
     *
     * This does not retry a specific failed reload. It only controls how quickly the service reconnects to the trigger
     * source.
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
     * Adds a listener for reload failures and trigger-loop failures.
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
