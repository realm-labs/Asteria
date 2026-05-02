package io.github.mikai233.asteria.config.luban

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mikai233.asteria.config.ConfigLoader
import io.github.mikai233.asteria.config.ConfigRevision
import io.github.mikai233.asteria.config.ConfigSnapshot
import io.github.mikai233.asteria.config.DefaultConfigSnapshot
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.reflect.KClass

class LubanJsonConfigLoader(
    private val tablesType: KClass<out Any>,
    private val dataSource: LubanDataSource,
    private val objectMapper: ObjectMapper = ObjectMapper(),
    private val charset: Charset = StandardCharsets.UTF_8,
    private val includeTableComponents: Boolean = true,
    private val revisionFactory: (LubanJsonLoadReport) -> ConfigRevision = { report ->
        ConfigRevision(version = report.checksum, checksum = report.checksum)
    },
) : ConfigLoader {
    override suspend fun load(): ConfigSnapshot {
        val sourceFiles = dataSource.list(".json")
        val loadedFiles = linkedMapOf<String, ByteArray>()
        val tables = instantiateLubanTables(tablesType, "AsteriaLubanJsonLoader") { file, returnType ->
            val name = fileNameWithExtension(file, ".json")
            val bytes = requireNotNull(sourceFiles[name]) { "Luban json data file $name not found" }
            loadedFiles[name] = bytes
            objectMapper.readTree(bytes.toString(charset)).also { json ->
                require(returnType.isInstance(json)) {
                    "Luban json loader return type ${returnType.name} is not compatible with ${json.javaClass.name}"
                }
            }
        }

        val report = LubanJsonLoadReport(
            files = loadedFiles.keys.toList(),
            checksum = checksumByName(loadedFiles),
        )
        val components = buildList {
            add(tables)
            if (includeTableComponents) {
                addAll(extractTableComponents(tables))
            }
        }

        return DefaultConfigSnapshot(
            revision = revisionFactory(report),
            components = components,
            componentsByType = mapOf(tablesType to tables),
        )
    }
}

data class LubanJsonLoadReport(
    override val files: List<String>,
    override val checksum: String,
) : LubanLoadReport
