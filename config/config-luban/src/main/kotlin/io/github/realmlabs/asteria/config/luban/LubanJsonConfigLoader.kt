package io.github.realmlabs.asteria.config.luban

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.realmlabs.asteria.config.*
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.reflect.KClass

/**
 * Loads Luban JSON exports into an Asteria [ConfigSnapshot].
 *
 * Each Luban `load("name")` call reads `name.json` from [dataSource], parses it with [objectMapper], and returns the
 * resulting Jackson node to the generated Luban loader. The generated loader method return type must accept that
 * Jackson value. Missing files, parse failures, or incompatible return types fail the whole snapshot load. The default
 * revision is a SHA-256 checksum over the files actually requested by the Luban tables, ordered by file name.
 */
class LubanJsonConfigLoader<T : Any, L : Any>(
    private val tablesType: KClass<T>,
    private val dataSource: LubanDataSource,
    private val bridge: LubanSnapshotBridge<T, L>,
    private val objectMapper: ObjectMapper = ObjectMapper(),
    private val charset: Charset = StandardCharsets.UTF_8,
    private val revisionFactory: (LubanJsonLoadReport) -> ConfigRevision = { report ->
        ConfigRevision(version = report.checksum, checksum = report.checksum)
    },
) : ConfigLoader {
    override suspend fun load(): ConfigSnapshot {
        val sourceFiles = dataSource.list(".json")
        val loadedFiles = linkedMapOf<String, ByteArray>()
        val tables = createLubanTables(bridge, "AsteriaLubanJsonLoader") { file, returnType ->
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
        return DefaultConfigSnapshot(
            revision = revisionFactory(report),
            entries = buildList {
                add(SnapshotEntry.Component(tables, tablesType))
                addAll(bridge.buildEntries(tables))
            },
        )
    }
}

/**
 * Load report passed to the JSON revision factory.
 */
data class LubanJsonLoadReport(
    override val files: List<String>,
    override val checksum: String,
) : LubanLoadReport
