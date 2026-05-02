package io.github.mikai233.asteria.config.luban

import io.github.mikai233.asteria.config.ConfigLoader
import io.github.mikai233.asteria.config.ConfigRevision
import io.github.mikai233.asteria.config.ConfigSnapshot
import io.github.mikai233.asteria.config.DefaultConfigSnapshot
import java.nio.file.Path
import kotlin.reflect.KClass

class LubanBinaryConfigLoader(
    private val tablesType: KClass<out Any>,
    private val dataDir: Path,
    private val fileResolver: (String) -> Path = { dataDir.resolve("$it.bytes") },
    private val preloadOptions: LubanPreloadOptions = LubanPreloadOptions(),
    private val includeTableComponents: Boolean = true,
    private val revisionFactory: (LubanBinaryLoadReport) -> ConfigRevision = { report ->
        ConfigRevision(version = report.checksum, checksum = report.checksum)
    },
) : ConfigLoader {
    override suspend fun load(): ConfigSnapshot {
        val preloadedFiles = preloadDataFiles(dataDir, ".bytes", preloadOptions)
        val loadedFiles = linkedMapOf<Path, ByteArray>()
        val tables = instantiateLubanTables(tablesType, "AsteriaLubanBinaryLoader") { file, returnType ->
            val path = fileResolver(file)
            val bytes = readDataFile(path, preloadedFiles, loadedFiles)
            returnType.newByteBuf(bytes)
        }

        val report = LubanBinaryLoadReport(
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

data class LubanBinaryLoadReport(
    override val dataDir: Path,
    override val files: List<Path>,
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
