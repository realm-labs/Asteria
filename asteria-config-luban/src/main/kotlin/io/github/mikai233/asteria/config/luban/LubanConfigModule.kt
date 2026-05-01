package io.github.mikai233.asteria.config.luban

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mikai233.asteria.config.ConfigService
import io.github.mikai233.asteria.config.ConfigSnapshot
import io.github.mikai233.asteria.config.ConfigRevision
import io.github.mikai233.asteria.config.ConfigValidationScope
import io.github.mikai233.asteria.config.ConfigValidator
import io.github.mikai233.asteria.config.configValidator
import io.github.mikai233.asteria.core.AsteriaDsl
import io.github.mikai233.asteria.core.AsteriaModule
import io.github.mikai233.asteria.core.ModuleContext
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.reflect.KClass

class LubanConfigModule private constructor(
    private val options: LubanConfigModuleOptions,
) : AsteriaModule {
    override val name: String = "config-luban"

    override suspend fun install(context: ModuleContext) {
        val tablesType = options.tablesType ?: error("Luban tables type must be configured")
        val dataDir = options.dataDir ?: error("Luban data dir must be configured")
        val loader = when (options.format) {
            LubanConfigFormat.Json -> LubanJsonConfigLoader(
                tablesType = tablesType,
                dataDir = dataDir,
                objectMapper = options.objectMapper,
                charset = options.charset,
                fileResolver = options.fileResolver,
                includeTableComponents = options.includeTableComponents,
                revisionFactory = { report -> options.revisionFactory(report) },
            )
            LubanConfigFormat.Binary -> LubanBinaryConfigLoader(
                tablesType = tablesType,
                dataDir = dataDir,
                fileResolver = options.fileResolver,
                includeTableComponents = options.includeTableComponents,
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

data class LubanConfigModuleOptions(
    val tablesType: KClass<out Any>?,
    val dataDir: Path?,
    val format: LubanConfigFormat,
    val objectMapper: ObjectMapper,
    val charset: Charset,
    val fileResolver: (String) -> Path,
    val includeTableComponents: Boolean,
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
    var includeTableComponents: Boolean = true
    var loadOnStart: Boolean = true

    private var tablesType: KClass<out Any>? = null
    private var dataDir: Path? = null
    private var fileResolver: ((Path, String) -> Path)? = null
    private var revisionFactory: (LubanLoadReport) -> ConfigRevision = { report ->
        ConfigRevision(version = report.checksum, checksum = report.checksum)
    }
    private val validators: MutableList<ConfigValidator> = mutableListOf()

    fun tables(type: KClass<out Any>) {
        tablesType = type
    }

    inline fun <reified T : Any> tables() {
        tables(T::class)
    }

    fun dataDir(path: Path) {
        dataDir = path
    }

    fun json() {
        format = LubanConfigFormat.Json
    }

    fun binary() {
        format = LubanConfigFormat.Binary
    }

    fun fileResolver(resolve: (Path, String) -> Path) {
        fileResolver = resolve
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
        val dataDir = dataDir
        return LubanConfigModuleOptions(
            tablesType = tablesType,
            dataDir = dataDir,
            format = format,
            objectMapper = objectMapper,
            charset = charset,
            fileResolver = { file ->
                val dir = dataDir ?: error("Luban data dir must be configured")
                fileResolver?.invoke(dir, file) ?: dir.resolve(fileName(file))
            },
            includeTableComponents = includeTableComponents,
            revisionFactory = revisionFactory,
            validators = validators.toList(),
            loadOnStart = loadOnStart,
        )
    }

    private fun fileName(file: String): String {
        return when (format) {
            LubanConfigFormat.Json -> "$file.json"
            LubanConfigFormat.Binary -> "$file.bytes"
        }
    }
}
