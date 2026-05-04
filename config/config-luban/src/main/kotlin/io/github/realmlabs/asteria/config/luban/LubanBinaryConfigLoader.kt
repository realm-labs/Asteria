package io.github.realmlabs.asteria.config.luban

import io.github.realmlabs.asteria.config.ConfigLoader
import io.github.realmlabs.asteria.config.ConfigRevision
import io.github.realmlabs.asteria.config.ConfigSnapshot
import io.github.realmlabs.asteria.config.DefaultConfigSnapshot
import io.github.realmlabs.asteria.config.SnapshotEntry
import kotlin.reflect.KClass

class LubanBinaryConfigLoader<T : Any, L : Any>(
    private val tablesType: KClass<T>,
    private val dataSource: LubanDataSource,
    private val bridge: LubanSnapshotBridge<T, L>,
    private val revisionFactory: (LubanBinaryLoadReport) -> ConfigRevision = { report ->
        ConfigRevision(version = report.checksum, checksum = report.checksum)
    },
) : ConfigLoader {
    override suspend fun load(): ConfigSnapshot {
        val sourceFiles = dataSource.list(".bytes")
        val loadedFiles = linkedMapOf<String, ByteArray>()
        val tables = createLubanTables(bridge, "AsteriaLubanBinaryLoader") { file, returnType ->
            val name = fileNameWithExtension(file, ".bytes")
            val bytes = requireNotNull(sourceFiles[name]) { "Luban binary data file $name not found" }
            loadedFiles[name] = bytes
            returnType.newByteBuf(bytes)
        }

        val report = LubanBinaryLoadReport(
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

data class LubanBinaryLoadReport(
    override val files: List<String>,
    override val checksum: String,
) : LubanLoadReport

private fun Class<*>.newByteBuf(bytes: ByteArray): Any {
    val constructor = constructors.firstOrNull { constructor ->
        val parameterTypes = constructor.parameterTypes
        parameterTypes.size == 1 && parameterTypes.single() == ByteArray::class.java
    } ?: error("Luban binary loader return type $name must have a byte-array constructor")
    constructor.trySetAccessible()
    return constructor.newInstance(bytes)
}
