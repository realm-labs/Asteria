package io.github.mikai233.asteria.config.luban

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mikai233.asteria.config.ConfigLoader
import io.github.mikai233.asteria.config.ConfigRevision
import io.github.mikai233.asteria.config.ConfigSnapshot
import io.github.mikai233.asteria.config.DefaultConfigSnapshot
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.reflect.KClass

class LubanJsonConfigLoader(
    private val tablesType: KClass<out Any>,
    private val dataDir: Path,
    private val objectMapper: ObjectMapper = ObjectMapper(),
    private val charset: Charset = StandardCharsets.UTF_8,
    private val fileResolver: (String) -> Path = { dataDir.resolve("$it.json") },
    private val preloadOptions: LubanPreloadOptions = LubanPreloadOptions(),
    private val includeTableComponents: Boolean = true,
    private val revisionFactory: (LubanJsonLoadReport) -> ConfigRevision = { report ->
        ConfigRevision(version = report.checksum, checksum = report.checksum)
    },
) : ConfigLoader {
    override suspend fun load(): ConfigSnapshot {
        val preloadedFiles = preloadDataFiles(dataDir, ".json", preloadOptions)
        val loadedFiles = linkedMapOf<Path, ByteArray>()
        val tables = instantiateLubanTables(tablesType, "AsteriaLubanJsonLoader") { file, returnType ->
            val path = fileResolver(file)
            val bytes = readDataFile(path, preloadedFiles, loadedFiles)
            objectMapper.readTree(bytes.toString(charset)).also { json ->
                require(returnType.isInstance(json)) {
                    "Luban json loader return type ${returnType.name} is not compatible with ${json.javaClass.name}"
                }
            }
        }

        val report = LubanJsonLoadReport(
            dataDir = dataDir,
            files = loadedFiles.keys.toList(),
            checksum = checksum(loadedFiles),
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
    override val dataDir: Path,
    override val files: List<Path>,
    override val checksum: String,
) : LubanLoadReport
