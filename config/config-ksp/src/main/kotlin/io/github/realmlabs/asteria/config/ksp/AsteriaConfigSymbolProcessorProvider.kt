package io.github.realmlabs.asteria.config.ksp

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import io.github.realmlabs.asteria.config.annotations.*

/**
 * KSP entry point for Asteria config table accessor and change-handler generation.
 *
 * The processor is aggregating: it scans all `@AsteriaConfigTable` and `@AsteriaConfigChangeHandler` symbols, emits
 * generated Kotlin sources, and writes a small JSON snapshot under `META-INF/asteria/codegen-snapshots/config` for
 * build tooling diagnostics.
 */
class AsteriaConfigSymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return AsteriaConfigSymbolProcessor(environment.codeGenerator, environment.logger, environment.options)
    }
}

private class AsteriaConfigSymbolProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>,
) : SymbolProcessor {
    private var generated = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) {
            return emptyList()
        }
        val tableSymbols = resolver.getSymbolsWithAnnotation(AsteriaConfigTable::class.qualifiedName!!)
            .filterIsInstance<KSClassDeclaration>()
            .toList()
        val handlerSymbols = resolver.getSymbolsWithAnnotation(AsteriaConfigChangeHandler::class.qualifiedName!!)
            .filterIsInstance<KSClassDeclaration>()
            .toList()
        val invalid = (tableSymbols + handlerSymbols).filterNot { it.validate() }
        if (invalid.isNotEmpty()) {
            return invalid
        }
        if (
            tableSymbols.isEmpty() &&
            handlerSymbols.isEmpty() &&
            !hasConfigChangeCatalog(resolver)
        ) {
            generated = true
            return emptyList()
        }

        var tableCodegenConfig: ConfigCodegenConfig? = null
        var tableModels = emptyList<ConfigTableModel>()
        val tableSourceFiles = tableSymbols.mapNotNull { it.containingFile }
        if (tableSymbols.isNotEmpty()) {
            val config = readCodegenConfig(resolver)
            val tables = tableSymbols.mapNotNull(::readTableModel)
            tableCodegenConfig = config
            tableModels = tables
            val sourceFiles = tableSymbols.mapNotNull { it.containingFile }.toTypedArray()
            AsteriaConfigCodeGenerator.buildFiles(config, tables).forEach { generated ->
                generated.file.writeTo(codeGenerator, Dependencies(aggregating = true, *sourceFiles))
            }
        }
        var changeSnapshot: ConfigChangeCodegenSnapshot? = null
        if (handlerSymbols.isNotEmpty() || hasConfigChangeCatalog(resolver)) {
            changeSnapshot = generateConfigChangeHandlers(resolver, handlerSymbols)
        }
        generateCodegenSnapshot(
            tableCodegenConfig = tableCodegenConfig,
            tables = tableModels,
            tableSourceFiles = tableSourceFiles,
            changeSnapshot = changeSnapshot,
        )
        generated = true
        return emptyList()
    }

    private fun generateConfigChangeHandlers(
        resolver: Resolver,
        handlerSymbols: List<KSClassDeclaration>,
    ): ConfigChangeCodegenSnapshot? {
        val processorConfig = readConfigChangeCodegenConfig(resolver) ?: return null
        val handlers = handlerSymbols.mapNotNull { handler ->
            readConfigChangeHandlerModel(
                resolver = resolver,
                declaration = handler,
                receiverType = processorConfig.receiverType,
            )
        }
        val sourceFiles = (handlerSymbols.mapNotNull { it.containingFile } + processorConfig.sourceFiles)
            .distinctBy { it.filePath }
            .toTypedArray()
        AsteriaConfigChangeCodeGenerator.buildFiles(processorConfig.codegen, handlers).forEach { generated ->
            generated.file.writeTo(codeGenerator, Dependencies(aggregating = true, *sourceFiles))
        }
        return ConfigChangeCodegenSnapshot(
            codegen = processorConfig.codegen,
            handlers = handlers,
            sourceFiles = sourceFiles.toList(),
        )
    }

    private fun generateCodegenSnapshot(
        tableCodegenConfig: ConfigCodegenConfig?,
        tables: List<ConfigTableModel>,
        tableSourceFiles: List<KSFile>,
        changeSnapshot: ConfigChangeCodegenSnapshot?,
    ) {
        val sourceFiles = (tableSourceFiles + changeSnapshot?.sourceFiles.orEmpty())
            .distinctBy { it.filePath }
            .toTypedArray()
        val output = codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = true, *sourceFiles),
            packageName = "META-INF/asteria/codegen-snapshots/config",
            fileName = "config",
            extensionName = "json",
        )
        output.bufferedWriter().use { writer ->
            writer.appendLine("{")
            writer.appendLine("  \"schemaVersion\": 1,")
            writer.appendLine("  \"kind\": \"config\",")
            if (tableCodegenConfig == null) {
                writer.appendLine("  \"tablesCodegen\": null,")
            } else {
                writer.appendLine("  \"tablesCodegen\": {")
                writer.appendLine("    \"packageName\": ${jsonString(tableCodegenConfig.packageName)},")
                writer.appendLine("    \"tablesObjectName\": ${jsonString(tableCodegenConfig.tablesObjectName)},")
                writer.appendLine("    \"accessorClassName\": ${jsonString(tableCodegenConfig.accessorClassName)}")
                writer.appendLine("  },")
            }
            writer.appendLine("  \"tables\": [")
            tables.sortedBy { it.tableName }.forEachIndexed { index, table ->
                writer.appendLine("    {")
                writer.appendLine("      \"name\": ${jsonString(table.tableName)},")
                writer.appendLine("      \"shape\": ${jsonString(table.shape.name)},")
                if (table.keyType == null) {
                    writer.appendLine("      \"keyType\": null,")
                } else {
                    writer.appendLine("      \"keyType\": ${jsonString(table.keyType.toString())},")
                }
                writer.appendLine("      \"rowType\": ${jsonString(table.rowType.toString())},")
                if (table.tableType == null) {
                    writer.appendLine("      \"tableType\": null,")
                } else {
                    writer.appendLine("      \"tableType\": ${jsonString(table.tableType.rawType.canonicalName)},")
                }
                writer.appendLine("      \"refName\": ${jsonString(table.refName)},")
                writer.appendLine("      \"propertyName\": ${jsonString(table.propertyName)}")
                writer.append("    }")
                if (index != tables.lastIndex) {
                    writer.append(',')
                }
                writer.appendLine()
            }
            writer.appendLine("  ],")
            if (changeSnapshot == null) {
                writer.appendLine("  \"changeCodegen\": null,")
                writer.appendLine("  \"changeHandlers\": []")
            } else {
                writer.appendLine("  \"changeCodegen\": {")
                writer.appendLine("    \"packageName\": ${jsonString(changeSnapshot.codegen.packageName)},")
                writer.appendLine("    \"className\": ${jsonString(changeSnapshot.codegen.className)},")
                writer.appendLine("    \"receiverType\": ${jsonString(changeSnapshot.codegen.receiverType.toString())}")
                writer.appendLine("  },")
                writer.appendLine("  \"changeHandlers\": [")
                changeSnapshot.handlers
                    .sortedBy { it.handlerType.canonicalName }
                    .forEachIndexed { index, handler ->
                        writer.append("    ${jsonString(handler.handlerType.canonicalName)}")
                        if (index != changeSnapshot.handlers.lastIndex) {
                            writer.append(',')
                        }
                        writer.appendLine()
                    }
                writer.appendLine("  ]")
            }
            writer.appendLine("}")
        }
    }

    private fun readCodegenConfig(resolver: Resolver): ConfigCodegenConfig {
        val catalogs = resolver.getSymbolsWithAnnotation(AsteriaConfigCatalog::class.qualifiedName!!)
            .filterIsInstance<KSClassDeclaration>()
            .toList()
        if (catalogs.size > 1) {
            logger.error("only one @AsteriaConfigCatalog is allowed", catalogs.drop(1).first())
        }
        val catalog = catalogs.firstOrNull()?.findAnnotation(AsteriaConfigCatalog::class.qualifiedName!!)
        return ConfigCodegenConfig(
            packageName = catalog?.stringArg("packageName")?.takeIf { it.isNotBlank() }
                ?: options["asteria.config.package"]
                ?: "io.github.realmlabs.asteria.generated.config",
            tablesObjectName = catalog?.stringArg("tablesObjectName")?.takeIf { it.isNotBlank() }
                ?: options["asteria.config.tables"]
                ?: "GameConfigTables",
            accessorClassName = catalog?.stringArg("accessorClassName")?.takeIf { it.isNotBlank() }
                ?: options["asteria.config.accessor"]
                ?: "GameConfigs",
        )
    }

    private fun readConfigChangeCodegenConfig(resolver: Resolver): ConfigChangeProcessorConfig? {
        val catalogs = configChangeCatalogs(resolver)
        if (catalogs.size > 1) {
            logger.error("only one @AsteriaConfigChangeCatalog is allowed", catalogs.drop(1).first())
        }
        val catalog = catalogs.firstOrNull()?.findAnnotation(AsteriaConfigChangeCatalog::class.qualifiedName!!)
        val receiverType = catalog?.typeKSTypeArg("receiverType")?.takeUnless { it.isNothingType() }
            ?: options["asteria.config.change.receiverType"]
                ?.takeIf { it.isNotBlank() }
                ?.let { receiverTypeName ->
                    resolver.getClassDeclarationByName(resolver.getKSNameFromString(receiverTypeName))
                        ?.asStarProjectedType()
                        ?: run {
                            logger.error("cannot resolve config change receiver type $receiverTypeName")
                            null
                        }
                }
        if (receiverType == null) {
            logger.error(
                "config change handler generation requires @AsteriaConfigChangeCatalog(receiverType = ...) " +
                        "or KSP option asteria.config.change.receiverType",
            )
            return null
        }
        return ConfigChangeProcessorConfig(
            codegen = ConfigChangeCodegenConfig(
                packageName = catalog?.stringArg("packageName")?.takeIf { it.isNotBlank() }
                    ?: options["asteria.config.change.package"]
                    ?: options["asteria.config.package"]
                    ?: "io.github.realmlabs.asteria.generated.config",
                className = catalog?.stringArg("className")?.takeIf { it.isNotBlank() }
                    ?: options["asteria.config.change.class"]
                    ?: "GeneratedConfigChangeHandlers",
                receiverType = receiverType.toTypeName(),
            ),
            receiverType = receiverType,
            sourceFiles = catalogs.mapNotNull { it.containingFile },
        )
    }

    private fun readConfigChangeHandlerModel(
        resolver: Resolver,
        declaration: KSClassDeclaration,
        receiverType: KSType,
    ): ConfigChangeHandlerModel? {
        if (declaration.classKind != ClassKind.CLASS) {
            logger.error("@AsteriaConfigChangeHandler only supports classes", declaration)
            return null
        }
        if (Modifier.ABSTRACT in declaration.modifiers) {
            logger.error("config change handler must be concrete", declaration)
            return null
        }
        if (declaration.getVisibility().name != "PUBLIC") {
            logger.error("config change handler must be public", declaration)
            return null
        }
        val zeroArgConstructor = declaration.primaryConstructor?.parameters?.isEmpty() ?: true
        if (!zeroArgConstructor) {
            logger.error("config change handler must have a zero-argument primary constructor", declaration)
            return null
        }
        val handlerInterface = resolver.getClassDeclarationByName(
            resolver.getKSNameFromString("io.github.realmlabs.asteria.config.ConfigChangeHandler"),
        ) ?: run {
            logger.error("cannot resolve ConfigChangeHandler")
            return null
        }
        val implemented = declaration.superTypes
            .map { it.resolve() }
            .firstOrNull { it.declaration.qualifiedName?.asString() == handlerInterface.qualifiedName?.asString() }
        if (implemented == null) {
            logger.error("@AsteriaConfigChangeHandler class must implement ConfigChangeHandler", declaration)
            return null
        }
        val typeArg = implemented.arguments.singleOrNull()?.type?.resolve()
        if (typeArg?.declaration?.qualifiedName?.asString() != receiverType.declaration.qualifiedName?.asString()) {
            logger.error(
                "config change handler must implement ConfigChangeHandler<${receiverType.declaration.qualifiedName?.asString()}>",
                declaration,
            )
            return null
        }
        return ConfigChangeHandlerModel(declaration.toClassName())
    }

    private fun readTableModel(symbol: KSClassDeclaration): ConfigTableModel? {
        val annotation = symbol.findAnnotation(AsteriaConfigTable::class.qualifiedName!!) ?: return null
        val tableName = annotation.stringArg("name")
        val shape = AsteriaConfigTableShape.valueOf(annotation.enumArg("shape", AsteriaConfigTableShape.KEYED.name))
        val keyType = annotation.typeKSTypeArg("keyType")
            ?.takeUnless { it.isNothingType() }
            ?.toTypeName()
            .also { keyType ->
                if (shape == AsteriaConfigTableShape.KEYED && keyType == null) {
                    logger.error("keyed config table $tableName requires keyType", symbol)
                }
            }
        val explicitRowType = annotation.typeKSTypeArg("rowType")
        val rowType = if (explicitRowType == null || explicitRowType.isNothingType()) {
            symbol.asStarProjectedType().toTypeName()
        } else {
            explicitRowType.toTypeName()
        }
        val tableType = annotation.typeKSTypeArg("tableType")
            ?.takeUnless { it.isNothingType() }
            ?.let { tableType ->
                readAccessorTableType(symbol, tableName, shape, tableType)
            }
        return ConfigTableModel(
            tableName = tableName,
            shape = shape,
            keyType = keyType,
            rowType = rowType,
            tableType = tableType,
            refName = annotation.stringArg("refName").takeIf { it.isNotBlank() }
                ?: tableName.toUpperCamelIdentifier(),
            propertyName = annotation.stringArg("propertyName").takeIf { it.isNotBlank() }
                ?: tableName.toLowerCamelIdentifier(),
        )
    }

    private fun readAccessorTableType(
        symbol: KSClassDeclaration,
        tableName: String,
        shape: AsteriaConfigTableShape,
        tableType: KSType,
    ): ConfigAccessorTableType? {
        val declaration = tableType.declaration as? KSClassDeclaration ?: run {
            logger.error("config table $tableName tableType must be a class", symbol)
            return null
        }
        val expectedSuperType = when (shape) {
            AsteriaConfigTableShape.KEYED -> "io.github.realmlabs.asteria.config.KeyedConfigTable"
            AsteriaConfigTableShape.LIST -> "io.github.realmlabs.asteria.config.ListConfigTable"
            AsteriaConfigTableShape.SINGLETON -> "io.github.realmlabs.asteria.config.SingleConfigTable"
        }
        if (!declaration.hasSuperType(expectedSuperType)) {
            logger.error("config table $tableName tableType must extend $expectedSuperType", symbol)
            return null
        }
        val typeArgumentCount = declaration.typeParameters.size
        val expectedTypeArgumentCount = when (shape) {
            AsteriaConfigTableShape.KEYED -> 2
            AsteriaConfigTableShape.LIST,
            AsteriaConfigTableShape.SINGLETON,
                -> 1
        }
        if (typeArgumentCount != 0 && typeArgumentCount != expectedTypeArgumentCount) {
            logger.error(
                "config table $tableName tableType must declare either 0 or $expectedTypeArgumentCount type parameters",
                symbol,
            )
            return null
        }
        return ConfigAccessorTableType(declaration.toClassName(), typeArgumentCount)
    }

    private fun hasConfigChangeCatalog(resolver: Resolver): Boolean {
        return configChangeCatalogs(resolver).isNotEmpty()
    }

    private fun configChangeCatalogs(resolver: Resolver): List<KSClassDeclaration> {
        return resolver.getSymbolsWithAnnotation(AsteriaConfigChangeCatalog::class.qualifiedName!!)
            .filterIsInstance<KSClassDeclaration>()
            .toList()
    }

    private fun KSClassDeclaration.findAnnotation(qualifiedName: String): KSAnnotation? {
        return annotations.firstOrNull { annotation ->
            annotation.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
        }
    }

    private fun KSAnnotation.stringArg(name: String): String {
        return arguments.firstOrNull { it.name?.asString() == name }?.value as? String
            ?: ""
    }

    private fun KSAnnotation.typeKSTypeArg(name: String): KSType? {
        return arguments.firstOrNull { it.name?.asString() == name }?.value as? KSType
    }

    private fun KSAnnotation.enumArg(name: String, default: String): String {
        val value = arguments.firstOrNull { it.name?.asString() == name }?.value ?: return default
        return value.toString().substringAfterLast('.')
    }

    private fun KSType.isNothingType(): Boolean {
        return declaration.qualifiedName?.asString() == "kotlin.Nothing"
    }

    private fun jsonString(value: String): String {
        return buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (char < ' ') {
                            append("\\u")
                            append(char.code.toString(16).padStart(4, '0'))
                        } else {
                            append(char)
                        }
                    }
                }
            }
            append('"')
        }
    }

    private fun KSClassDeclaration.hasSuperType(qualifiedName: String): Boolean {
        if (this.qualifiedName?.asString() == qualifiedName) {
            return true
        }
        return superTypes
            .mapNotNull { it.resolve().declaration as? KSClassDeclaration }
            .any { it.hasSuperType(qualifiedName) }
    }
}

private data class ConfigChangeProcessorConfig(
    val codegen: ConfigChangeCodegenConfig,
    val receiverType: KSType,
    val sourceFiles: List<KSFile>,
)

private data class ConfigChangeCodegenSnapshot(
    val codegen: ConfigChangeCodegenConfig,
    val handlers: List<ConfigChangeHandlerModel>,
    val sourceFiles: List<KSFile>,
)
