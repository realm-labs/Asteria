package io.github.mikai233.asteria.config.gradle

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

object AsteriaLubanConfigMarkerGenerator {
    fun generate(config: LubanMarkerGeneratorConfig, tables: List<LubanConfigTableSpec>): Path {
        require(tables.isNotEmpty()) { "Luban config marker metadata must contain at least one table" }
        val outputFile = config.outputDirectory
            .resolve(config.packageName.replace('.', '/'))
            .also(Path::createDirectories)
            .resolve("${config.fileName}.kt")
        outputFile.writeText(buildSource(config, tables))
        return outputFile
    }

    fun buildSource(config: LubanMarkerGeneratorConfig, tables: List<LubanConfigTableSpec>): String {
        val sortedTables = tables.sortedBy { it.name }
        val markerNames = sortedTables.map { it.markerName }
        val duplicateMarkerName = markerNames.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
        require(duplicateMarkerName == null) { "duplicate generated Luban marker object name $duplicateMarkerName" }
        return buildString {
            appendLine("package ${config.packageName}")
            appendLine()
            appendLine("import io.github.mikai233.asteria.config.annotations.AsteriaConfigCatalog")
            appendLine("import io.github.mikai233.asteria.config.annotations.AsteriaConfigTable")
            appendLine()
            appendLine("@AsteriaConfigCatalog(")
            appendLine("    packageName = ${config.packageName.kotlinString()},")
            appendLine("    tablesObjectName = ${config.tablesObjectName.kotlinString()},")
            appendLine("    accessorClassName = ${config.accessorClassName.kotlinString()},")
            appendLine(")")
            appendLine("object ${config.fileName}Catalog")
            sortedTables.forEach { table ->
                appendLine()
                appendLine("@AsteriaConfigTable(")
                appendLine("    name = ${table.name.kotlinString()},")
                appendLine("    keyType = ${table.keyType}::class,")
                appendLine("    rowType = ${table.rowType}::class,")
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
}

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

data class LubanConfigTableSpec(
    val name: String,
    val keyType: String,
    val rowType: String,
    val refName: String = "",
    val propertyName: String = "",
    val markerName: String = "${name.toUpperCamelIdentifier()}Table",
) {
    init {
        require(name.isNotBlank()) { "Luban config table name must not be blank" }
        require(keyType.isValidQualifiedClassName()) { "invalid Luban config key type $keyType" }
        require(rowType.isValidQualifiedClassName()) { "invalid Luban config row type $rowType" }
        require(refName.isBlank() || refName.isValidKotlinIdentifier()) { "invalid generated config ref name $refName" }
        require(propertyName.isBlank() || propertyName.isValidKotlinIdentifier()) {
            "invalid generated config property name $propertyName"
        }
        require(markerName.isValidKotlinIdentifier()) { "invalid generated Luban marker object name $markerName" }
    }
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
