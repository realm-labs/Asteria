package io.github.realmlabs.asteria.config.gradle

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.File
import javax.inject.Inject

/**
 * Cacheable Gradle task that turns Luban table metadata JSON into annotation marker sources.
 *
 * The task is a no-op when marker generation is disabled. When enabled, it deletes the previous generated output
 * directory before writing fresh sources so stale table markers cannot survive metadata changes.
 */
@CacheableTask
abstract class AsteriaLubanConfigMarkerTask : DefaultTask() {
    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @get:Input
    abstract val generationEnabled: Property<Boolean>

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val metadataFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val fileName: Property<String>

    @get:Input
    abstract val tablesObjectName: Property<String>

    @get:Input
    abstract val accessorClassName: Property<String>

    init {
        onlyIf { generationEnabled.get() }
    }

    /**
     * Reads metadata, validates table specs, and writes generated marker sources.
     */
    @TaskAction
    fun generate() {
        val metadata = metadataFile.orNull?.asFile
            ?: error("Luban config marker generation is enabled but metadataFile is not configured")
        val tables = readTables(metadata)
        fileSystemOperations.delete {
            it.delete(outputDirectory)
        }
        AsteriaLubanConfigMarkerGenerator.generate(
            config = LubanMarkerGeneratorConfig(
                outputDirectory = outputDirectory.get().asFile.toPath(),
                packageName = packageName.get(),
                fileName = fileName.get(),
                tablesObjectName = tablesObjectName.get(),
                accessorClassName = accessorClassName.get(),
            ),
            tables = tables,
        )
    }

    private fun readTables(file: File): List<LubanConfigTableSpec> {
        val tableValues = when (val root = JsonSlurper().parse(file)) {
            is List<*> -> root
            is Map<*, *> -> root["tables"] as? List<*>
                ?: error("Luban config marker metadata must contain a tables array")

            else -> error("Luban config marker metadata must be a JSON object or array")
        }
        return tableValues.mapIndexed { index, value ->
            val table = value as? Map<*, *>
                ?: error("Luban config marker table at index $index must be an object")
            LubanConfigTableSpec(
                name = table.requiredString("name", index),
                shape = table.optionalString("shape")
                    .takeIf { it.isNotBlank() }
                    ?.let { LubanConfigTableShape.valueOf(it.uppercase()) }
                    ?: LubanConfigTableShape.KEYED,
                keyType = table.optionalString("keyType"),
                rowType = table.requiredString("rowType", index),
                tableType = table.optionalString("tableType"),
                refName = table.optionalString("refName"),
                propertyName = table.optionalString("propertyName"),
                markerName = table.optionalString("markerName")
                    .takeIf { it.isNotBlank() }
                    ?: "${table.requiredString("name", index).toUpperCamelIdentifier()}Table",
            )
        }
    }

    private fun Map<*, *>.requiredString(name: String, index: Int): String {
        return optionalString(name).ifBlank {
            error("Luban config marker table at index $index must contain non-blank $name")
        }
    }

    private fun Map<*, *>.optionalString(name: String): String {
        return this[name]?.toString() ?: ""
    }
}
