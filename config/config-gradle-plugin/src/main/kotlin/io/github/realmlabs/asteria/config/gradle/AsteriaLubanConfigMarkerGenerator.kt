package io.github.realmlabs.asteria.config.gradle

import io.github.realmlabs.asteria.config.gradle.AsteriaLubanConfigMarkerGenerator.buildSources
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Generates Kotlin marker sources from Luban table metadata.
 *
 * The generated markers carry `@AsteriaConfigTable` annotations so the KSP processor can create normal Asteria table
 * references and accessors without depending on Luban internals. Large metadata sets are split into chunks while the
 * catalog annotation remains in the main file.
 */
object AsteriaLubanConfigMarkerGenerator {
    /**
     * Writes generated marker sources to [LubanMarkerGeneratorConfig.outputDirectory].
     */
    fun generate(config: LubanMarkerGeneratorConfig, tables: List<LubanConfigTableSpec>): Path {
        require(tables.isNotEmpty()) { "Luban config marker metadata must contain at least one table" }
        val packageDirectory = config.outputDirectory
            .resolve(config.packageName.replace('.', '/'))
            .also(Path::createDirectories)
        val generated = buildSources(config, tables)
        generated.forEach { source ->
            packageDirectory.resolve("${source.fileName}.kt").writeText(source.source)
        }
        return packageDirectory.resolve("${config.fileName}.kt")
    }

    /**
     * Builds a single marker source file.
     *
     * Call [buildSources] when metadata may exceed the chunk threshold.
     */
    fun buildSource(config: LubanMarkerGeneratorConfig, tables: List<LubanConfigTableSpec>): String {
        val sortedTables = tables.sortedBy { it.name }
        validateTables(sortedTables)
        return buildSingleSource(config, sortedTables)
    }

    /**
     * Builds all marker source files, chunking table markers when needed.
     */
    fun buildSources(
        config: LubanMarkerGeneratorConfig,
        tables: List<LubanConfigTableSpec>,
    ): List<LubanMarkerGeneratedSource> {
        val sortedTables = tables.sortedBy { it.name }
        validateTables(sortedTables)
        if (sortedTables.size <= MARKER_CHUNK_SIZE) {
            return listOf(LubanMarkerGeneratedSource(config.fileName, buildSource(config, sortedTables)))
        }
        return listOf(LubanMarkerGeneratedSource(config.fileName, buildCatalogSource(config))) +
                sortedTables.chunked(MARKER_CHUNK_SIZE).mapIndexed { index, chunk ->
                    LubanMarkerGeneratedSource(
                        fileName = "${config.fileName}Chunk$index",
                        source = buildChunkSource(config, chunk),
                    )
                }
    }

    private fun validateTables(sortedTables: List<LubanConfigTableSpec>) {
        val markerNames = sortedTables.map { it.markerName }
        val duplicateMarkerName = markerNames.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
        require(duplicateMarkerName == null) { "duplicate generated Luban marker object name $duplicateMarkerName" }
    }

    private fun buildCatalogSource(config: LubanMarkerGeneratorConfig): String {
        return buildString {
            appendLine("package ${config.packageName}")
            appendLine()
            appendLine("import io.github.realmlabs.asteria.config.annotations.AsteriaConfigCatalog")
            appendLine()
            appendCatalogDeclaration(config)
        }
    }

    private fun buildSingleSource(
        config: LubanMarkerGeneratorConfig,
        tables: List<LubanConfigTableSpec>,
    ): String {
        return buildString {
            appendLine("package ${config.packageName}")
            appendLine()
            appendLine("import io.github.realmlabs.asteria.config.annotations.AsteriaConfigCatalog")
            appendLine("import io.github.realmlabs.asteria.config.annotations.AsteriaConfigTable")
            appendLine("import io.github.realmlabs.asteria.config.annotations.AsteriaConfigTableShape")
            appendLine()
            appendCatalogDeclaration(config)
            append(buildMarkerSource(tables))
        }
    }

    private fun buildChunkSource(
        config: LubanMarkerGeneratorConfig,
        tables: List<LubanConfigTableSpec>,
    ): String {
        return buildString {
            appendLine("package ${config.packageName}")
            appendLine()
            appendLine("import io.github.realmlabs.asteria.config.annotations.AsteriaConfigTable")
            appendLine("import io.github.realmlabs.asteria.config.annotations.AsteriaConfigTableShape")
            append(buildMarkerSource(tables))
        }
    }

    private fun buildMarkerSource(tables: List<LubanConfigTableSpec>): String {
        return buildString {
            tables.forEach { table ->
                appendLine()
                appendLine("@AsteriaConfigTable(")
                appendLine("    name = ${table.name.kotlinString()},")
                if (table.shape != LubanConfigTableShape.KEYED) {
                    appendLine("    shape = AsteriaConfigTableShape.${table.shape.name},")
                }
                if (table.shape == LubanConfigTableShape.KEYED) {
                    appendLine("    keyType = ${table.keyType}::class,")
                }
                appendLine("    rowType = ${table.rowType}::class,")
                if (table.tableType.isNotBlank()) {
                    appendLine("    tableType = ${table.tableType}::class,")
                }
                if (table.refName.isNotBlank()) {
                    appendLine("    refName = ${table.refName.kotlinString()},")
                }
                if (table.propertyName.isNotBlank()) {
                    appendLine("    propertyName = ${table.propertyName.kotlinString()},")
                }
                appendLine(")")
                appendLine("object ${table.markerName}")
            }
        }
    }

    private fun StringBuilder.appendCatalogDeclaration(config: LubanMarkerGeneratorConfig) {
        appendLine("@AsteriaConfigCatalog(")
        appendLine("    packageName = ${config.packageName.kotlinString()},")
        appendLine("    tablesObjectName = ${config.tablesObjectName.kotlinString()},")
        appendLine("    accessorClassName = ${config.accessorClassName.kotlinString()},")
        appendLine(")")
        appendLine("object ${config.fileName}Catalog")
    }

    private const val MARKER_CHUNK_SIZE = 200
}

/**
 * One generated Kotlin source file and its logical file name without `.kt`.
 */
data class LubanMarkerGeneratedSource(
    val fileName: String,
    val source: String,
)

/**
 * Naming and output configuration for Luban marker generation.
 */
data class LubanMarkerGeneratorConfig(
    val outputDirectory: Path,
    val packageName: String,
    val fileName: String,
    val tablesObjectName: String,
    val accessorClassName: String,
) {
    init {
        require(packageName.isNotBlank()) { "generated Luban marker package name must not be blank" }
        require(fileName.isValidKotlinIdentifier()) { "invalid generated Luban marker file name $fileName" }
        require(tablesObjectName.isValidKotlinIdentifier()) { "invalid generated config tables object name $tablesObjectName" }
        require(accessorClassName.isValidKotlinIdentifier()) { "invalid generated config accessor class name $accessorClassName" }
    }
}

/**
 * Table metadata consumed by [AsteriaLubanConfigMarkerGenerator].
 *
 * Class-name properties must be fully qualified names visible to the target source set. Blank [refName] and
 * [propertyName] values let the downstream KSP generator derive stable Kotlin names from [name].
 */
data class LubanConfigTableSpec(
    val name: String,
    val shape: LubanConfigTableShape = LubanConfigTableShape.KEYED,
    val keyType: String = "",
    val rowType: String,
    val tableType: String = "",
    val refName: String = "",
    val propertyName: String = "",
    val markerName: String = "${name.toUpperCamelIdentifier()}Table",
) {
    init {
        require(name.isNotBlank()) { "Luban config table name must not be blank" }
        require(shape != LubanConfigTableShape.KEYED || keyType.isValidQualifiedClassName()) {
            "keyed Luban config table $name requires keyType"
        }
        require(shape == LubanConfigTableShape.KEYED || keyType.isBlank()) {
            "Luban config table $name must not declare keyType when shape is $shape"
        }
        require(rowType.isValidQualifiedClassName()) { "invalid Luban config row type $rowType" }
        require(tableType.isBlank() || tableType.isValidQualifiedClassName()) {
            "invalid Luban config table type $tableType"
        }
        require(refName.isBlank() || refName.isValidKotlinIdentifier()) { "invalid generated config ref name $refName" }
        require(propertyName.isBlank() || propertyName.isValidKotlinIdentifier()) {
            "invalid generated config property name $propertyName"
        }
        require(markerName.isValidKotlinIdentifier()) { "invalid generated Luban marker object name $markerName" }
    }
}

/**
 * Table shape declared by Luban metadata before it is translated to `AsteriaConfigTableShape`.
 */
enum class LubanConfigTableShape {
    KEYED,
    LIST,
    SINGLETON,
}

internal fun String.toUpperCamelIdentifier(): String {
    return split(Regex("[^A-Za-z0-9]+"))
        .filter { it.isNotBlank() }
        .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        .ifBlank { "_" }
        .let { if (it.firstOrNull()?.isDigit() == true) "_$it" else it }
}

internal fun String.isValidKotlinIdentifier(): Boolean {
    if (isBlank()) {
        return false
    }
    val first = first()
    return (first == '_' || first.isLetter()) && drop(1).all { it == '_' || it.isLetterOrDigit() }
}

private fun String.isValidQualifiedClassName(): Boolean {
    return split('.').all { it.isValidKotlinIdentifier() }
}

private fun String.kotlinString(): String {
    return buildString {
        append('"')
        for (char in this@kotlinString) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }
}
