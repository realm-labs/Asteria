package io.github.realmlabs.asteria.config.luban

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.realmlabs.asteria.config.*
import io.github.realmlabs.asteria.core.AsteriaDsl
import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.reflect.KClass

/**
 * Installs a [ConfigService] backed by Luban JSON or binary exports.
 *
 * The module requires a Luban root tables type, a [LubanSnapshotBridge], and either [LubanConfigModuleBuilder.dataDir]
 * or [LubanConfigModuleBuilder.dataSource]. It performs an initial load by default but does not install hot reload; use
 * `ConfigModule` with a Luban loader when config-center or file-watch reload behavior is needed.
 */
class LubanConfigModule private constructor(
    private val options: LubanConfigModuleOptions,
) : AsteriaModule {
    override val name: String = "config-luban"

    override suspend fun install(context: ModuleContext) {
        val tablesType = options.tablesType ?: error("Luban tables type must be configured")
        val dataSource = options.dataSource ?: error("Luban data source must be configured")
        val bridge = options.bridge ?: error("Luban snapshot bridge must be configured")
        val loader = when (options.format) {
            LubanConfigFormat.Json -> LubanJsonConfigLoader(
                tablesType = tablesType,
                dataSource = dataSource,
                bridge = bridge,
                objectMapper = options.objectMapper,
                charset = options.charset,
                revisionFactory = { report -> options.revisionFactory(report) },
            )

            LubanConfigFormat.Binary -> LubanBinaryConfigLoader(
                tablesType = tablesType,
                dataSource = dataSource,
                bridge = bridge,
                revisionFactory = { report -> options.revisionFactory(report) },
            )
        }
        context.services.register(
            ConfigService::class,
            ConfigService(loader, options.validators),
        )
    }

    override suspend fun start(context: ModuleContext) {
        if (options.loadOnStart) {
            context.services.get(ConfigService::class).load()
        }
    }

    companion object {
        operator fun invoke(configure: LubanConfigModuleBuilder.() -> Unit = {}): LubanConfigModule {
            return LubanConfigModule(LubanConfigModuleBuilder().apply(configure).build())
        }
    }
}

/**
 * Immutable options for [LubanConfigModule].
 */
data class LubanConfigModuleOptions(
    val tablesType: KClass<Any>?,
    val dataSource: LubanDataSource?,
    val bridge: LubanSnapshotBridge<Any, Any>?,
    val format: LubanConfigFormat,
    val objectMapper: ObjectMapper,
    val charset: Charset,
    val revisionFactory: (LubanLoadReport) -> ConfigRevision,
    val validators: List<ConfigValidator>,
    val loadOnStart: Boolean,
)

enum class LubanConfigFormat {
    Json,
    Binary,
}

@AsteriaDsl
class LubanConfigModuleBuilder {
    var format: LubanConfigFormat = LubanConfigFormat.Json
    var charset: Charset = StandardCharsets.UTF_8
    var objectMapper: ObjectMapper = ObjectMapper()
    var preload: Boolean = true
    var preloadConcurrency: Int = 4
    var loadOnStart: Boolean = true

    private var tablesType: KClass<Any>? = null
    private var dataDir: Path? = null
    private var dataSource: LubanDataSource? = null
    private var bridge: LubanSnapshotBridge<Any, Any>? = null
    private var revisionFactory: (LubanLoadReport) -> ConfigRevision = { report ->
        ConfigRevision(version = report.checksum, checksum = report.checksum)
    }
    private val validators: MutableList<ConfigValidator> = mutableListOf()

    fun <T : Any> tables(type: KClass<T>) {
        @Suppress("UNCHECKED_CAST")
        tablesType = type as KClass<Any>
    }

    fun <T : Any> tables(
        type: KClass<T>,
        bridge: LubanSnapshotBridge<T, *>,
    ) {
        tables(type)
        bridge(bridge)
    }

    inline fun <reified T : Any> tables() {
        tables(T::class)
    }

    inline fun <reified T : Any> tables(bridge: LubanSnapshotBridge<T, *>) {
        tables(T::class, bridge)
    }

    fun <T : Any> bridge(bridge: LubanSnapshotBridge<T, *>) {
        @Suppress("UNCHECKED_CAST")
        this.bridge = bridge as LubanSnapshotBridge<Any, Any>
    }

    fun dataDir(path: Path) {
        dataDir = path
        dataSource = null
    }

    fun dataSource(source: LubanDataSource) {
        dataSource = source
        dataDir = null
    }

    fun memory(files: Map<String, ByteArray>) {
        dataSource(MemoryLubanDataSource(files))
    }

    fun json() {
        format = LubanConfigFormat.Json
    }

    fun binary() {
        format = LubanConfigFormat.Binary
    }

    fun preload(
        enabled: Boolean = true,
        maxConcurrency: Int = 4,
    ) {
        preload = enabled
        preloadConcurrency = maxConcurrency
    }

    fun revision(factory: (LubanLoadReport) -> ConfigRevision) {
        revisionFactory = factory
    }

    fun validator(validator: ConfigValidator) {
        validators += validator
    }

    fun validator(validate: suspend ConfigValidationScope.(ConfigSnapshot) -> Unit) {
        validators += configValidator(validate)
    }

    internal fun build(): LubanConfigModuleOptions {
        val source = dataSource ?: dataDir?.let { dir ->
            DirectoryLubanDataSource(
                dataDir = dir,
                preloadOptions = LubanPreloadOptions(
                    enabled = preload,
                    maxConcurrency = preloadConcurrency,
                ),
            )
        }
        return LubanConfigModuleOptions(
            tablesType = tablesType,
            dataSource = source,
            bridge = bridge,
            format = format,
            objectMapper = objectMapper,
            charset = charset,
            revisionFactory = revisionFactory,
            validators = validators.toList(),
            loadOnStart = loadOnStart,
        )
    }
}
