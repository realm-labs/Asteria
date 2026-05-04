package io.github.realmlabs.asteria.config.luban

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.realmlabs.asteria.config.ConfigLoader
import io.github.realmlabs.asteria.config.ConfigRevision
import io.github.realmlabs.asteria.config.ConfigSnapshot
import io.github.realmlabs.asteria.config.DefaultConfigSnapshot
import io.github.realmlabs.asteria.config.SnapshotEntry
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.reflect.KClass

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

data class LubanJsonLoadReport(
    override val files: List<String>,
    override val checksum: String,
) : LubanLoadReport
