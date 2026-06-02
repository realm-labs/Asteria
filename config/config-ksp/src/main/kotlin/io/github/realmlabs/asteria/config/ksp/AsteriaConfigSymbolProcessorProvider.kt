package io.github.realmlabs.asteria.config.ksp

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import io.github.realmlabs.asteria.config.annotations.*
import io.github.realmlabs.asteria.ksp.*

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
    private val diagnostics = AsteriaKspDiagnostics(logger, "config-ksp")
    private var generated = false
    private var pendingDeferred: List<AsteriaKspDeferredSymbol> = emptyList()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) {
            return emptyList()
        }
        val tableClassReads = resolver.getSymbolsWithAnnotation(AsteriaConfigTable::class.qualifiedName!!)
            .map { symbol ->
                symbol.asAnnotatedClassOrInvalid(
                    diagnostics = diagnostics,
                    code = "ASTERIA-CONFIG-020",
                    annotationName = "@AsteriaConfigTable",
                )
            }
            .toList()
        val handlerClassReads = resolver.getSymbolsWithAnnotation(AsteriaConfigChangeHandler::class.qualifiedName!!)
            .map { symbol ->
                symbol.asAnnotatedClassOrInvalid(
                    diagnostics = diagnostics,
                    code = "ASTERIA-CONFIG-021",
                    annotationName = "@AsteriaConfigChangeHandler",
                )
            }
            .toList()
        val classDeferred = (tableClassReads + handlerClassReads).deferredSymbols()
        if (classDeferred.isNotEmpty()) {
            pendingDeferred = classDeferred
            return classDeferred.map { it.symbol }
        }
        val tableSymbols = tableClassReads.successfulValues()
        val handlerSymbols = handlerClassReads.successfulValues()
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
        val tableSourceFiles = tableSymbols.mapNotNull { it.requiredContainingFile("@AsteriaConfigTable") }
        if (tableSymbols.isNotEmpty()) {
            val config = readCodegenConfig(resolver)
            val tableReads = tableSymbols.map { symbol ->
                readOrDefer(symbol, "config table ${symbol.qualifiedName?.asString()}") {
                    readTableModel(symbol)
                }
            }
            val tableDeferred = tableReads.deferredSymbols()
            if (tableDeferred.isNotEmpty()) {
                pendingDeferred = tableDeferred
                return tableDeferred.map { it.symbol }
            }
            val tables = tableReads.successfulValues()
            if (!validateTableModels(tables)) {
                generated = true
                return emptyList()
            }
            tableCodegenConfig = config
            tableModels = tables
            val sourceFiles =
                tableSymbols.mapNotNull { it.requiredContainingFile("@AsteriaConfigTable") }.toTypedArray()
            AsteriaConfigCodeGenerator.buildFiles(config, tables).forEach { generated ->
                generated.file.writeTo(codeGenerator, Dependencies(aggregating = true, *sourceFiles))
            }
        }
        var changeSnapshot: ConfigChangeCodegenSnapshot? = null
        if (handlerSymbols.isNotEmpty() || hasConfigChangeCatalog(resolver)) {
            changeSnapshot = generateConfigChangeHandlers(resolver, handlerSymbols)
            val deferred = pendingDeferred
            if (deferred.isNotEmpty()) {
                return deferred.map { it.symbol }
            }
        }
        generateCodegenSnapshot(
            tableCodegenConfig = tableCodegenConfig,
            tables = tableModels,
            tableSourceFiles = tableSourceFiles,
            changeSnapshot = changeSnapshot,
        )
        generated = true
        pendingDeferred = emptyList()
        return emptyList()
    }

    override fun finish() {
        diagnostics.reportUnprocessedDeferredSymbols(
            code = "ASTERIA-CONFIG-022",
            message = "Config KSP symbol could not be processed after KSP rounds completed.",
            deferred = pendingDeferred,
            fix = "Check that the annotated class, catalog, receiver type, and referenced table types are resolvable in this module.",
        )
    }

    private fun <T : Any> readOrDefer(
        symbol: KSAnnotated,
        label: String,
        read: () -> T?,
    ): AsteriaKspSymbolRead<T> {
        return try {
            read()?.let { AsteriaKspSymbolRead.Success(it) } ?: AsteriaKspSymbolRead.Invalid
        } catch (error: Throwable) {
            AsteriaKspSymbolRead.Deferred(
                AsteriaKspDeferredSymbol(
                    symbol = symbol,
                    reason = "$label could not be resolved in this KSP round: ${error.message ?: error::class.simpleName}",
                ),
            )
        }
    }

    private fun generateConfigChangeHandlers(
        resolver: Resolver,
        handlerSymbols: List<KSClassDeclaration>,
    ): ConfigChangeCodegenSnapshot? {
        val processorConfig = readConfigChangeCodegenConfig(resolver) ?: return null
        val handlerReads = handlerSymbols.map { handler ->
            readOrDefer(handler, "config change handler ${handler.qualifiedName?.asString()}") {
                readConfigChangeHandlerModel(
                    resolver = resolver,
                    declaration = handler,
                    receiverType = processorConfig.receiverType,
                )
            }
        }
        val deferred = handlerReads.deferredSymbols()
        if (deferred.isNotEmpty()) {
            pendingDeferred = deferred
            return null
        }
        val handlers = handlerReads.successfulValues()
        if (!validateConfigChangeHandlerModels(handlers, handlerSymbols.firstOrNull())) {
            return null
        }
        val sourceFiles = (
                handlerSymbols.mapNotNull { it.requiredContainingFile("@AsteriaConfigChangeHandler") } +
                        processorConfig.sourceFiles
                )
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
            .map { symbol ->
                symbol.asAnnotatedClassOrInvalid(
                    diagnostics = diagnostics,
                    code = "ASTERIA-CONFIG-025",
                    annotationName = "@AsteriaConfigCatalog",
                )
            }
            .toList()
            .successfulValues()
        if (catalogs.size > 1) {
            diagnostics.error(
                code = "ASTERIA-CONFIG-001",
                message = "Only one @AsteriaConfigCatalog is allowed.",
                symbol = catalogs.drop(1).first(),
                reason = "The config table catalog controls the generated package, table object, and accessor class for this module.",
                fix = "Keep one catalog declaration and remove or merge the others.",
            )
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
            diagnostics.error(
                code = "ASTERIA-CONFIG-002",
                message = "Only one @AsteriaConfigChangeCatalog is allowed.",
                symbol = catalogs.drop(1).first(),
                reason = "The change catalog controls the generated handler list and receiver type for this module.",
                fix = "Keep one change catalog declaration and remove or merge the others.",
            )
        }
        val catalog = catalogs.firstOrNull()?.findAnnotation(AsteriaConfigChangeCatalog::class.qualifiedName!!)
        val receiverType = catalog?.typeKSTypeArg("receiverType")?.takeUnless { it.isNothingType() }
            ?: options["asteria.config.change.receiverType"]
                ?.takeIf { it.isNotBlank() }
                ?.let { receiverTypeName ->
                    resolver.getClassDeclarationByName(resolver.getKSNameFromString(receiverTypeName))
                        ?.asStarProjectedType()
                        ?: run {
                            diagnostics.error(
                                code = "ASTERIA-CONFIG-003",
                                message = "Cannot resolve config change receiver type.",
                                reason = "KSP option asteria.config.change.receiverType points to $receiverTypeName, but that class is not visible to KSP.",
                                fix = "Use the fully-qualified receiver class name and ensure the dependency is on the compile classpath.",
                            )
                            null
                        }
                }
        if (receiverType == null) {
            diagnostics.error(
                code = "ASTERIA-CONFIG-004",
                message = "Config change handler generation requires a receiver type.",
                reason = "@AsteriaConfigChangeHandler is generic over the receiver that will receive snapshots.",
                fix = "Add @AsteriaConfigChangeCatalog(receiverType = YourReceiver::class), or configure ksp option asteria.config.change.receiverType.",
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
            sourceFiles = catalogs.mapNotNull { it.requiredContainingFile("@AsteriaConfigChangeCatalog") },
        )
    }

    private fun readConfigChangeHandlerModel(
        resolver: Resolver,
        declaration: KSClassDeclaration,
        receiverType: KSType,
    ): ConfigChangeHandlerModel? {
        if (declaration.classKind != ClassKind.CLASS) {
            diagnostics.error(
                code = "ASTERIA-CONFIG-005",
                message = "@AsteriaConfigChangeHandler only supports classes.",
                symbol = declaration,
                reason = "Generated change handler lists instantiate handler classes with a constructor call.",
                fix = "Move the annotation to a public concrete class.",
            )
            return null
        }
        if (Modifier.ABSTRACT in declaration.modifiers) {
            diagnostics.error(
                code = "ASTERIA-CONFIG-006",
                message = "Config change handler must be concrete.",
                symbol = declaration,
                reason = "Abstract handlers cannot be instantiated in the generated handler list.",
                fix = "Annotate a concrete implementation class.",
            )
            return null
        }
        if (declaration.getVisibility().name != "PUBLIC") {
            diagnostics.error(
                code = "ASTERIA-CONFIG-007",
                message = "Config change handler must be public.",
                symbol = declaration,
                reason = "The generated handler list may be used from another package.",
                fix = "Make the handler class public.",
            )
            return null
        }
        val zeroArgConstructor = declaration.primaryConstructor?.parameters?.isEmpty() ?: true
        if (!zeroArgConstructor) {
            diagnostics.error(
                code = "ASTERIA-CONFIG-008",
                message = "Config change handler must have a zero-argument primary constructor.",
                symbol = declaration,
                reason = "The generated list creates handler instances directly.",
                fix = "Add a zero-argument primary constructor, or move dependencies behind a service looked up by the receiver.",
            )
            return null
        }
        val handlerInterface = resolver.getClassDeclarationByName(
            resolver.getKSNameFromString("io.github.realmlabs.asteria.config.ConfigChangeHandler"),
        ) ?: run {
            diagnostics.error(
                code = "ASTERIA-CONFIG-009",
                message = "Cannot resolve ConfigChangeHandler.",
                reason = "The config runtime dependency is missing from the KSP compile classpath.",
                fix = "Ensure the module depends on config-core when using @AsteriaConfigChangeHandler.",
            )
            return null
        }
        val implemented = declaration.superTypes
            .map { it.resolve() }
            .firstOrNull { it.declaration.qualifiedName?.asString() == handlerInterface.qualifiedName?.asString() }
        if (implemented == null) {
            diagnostics.error(
                code = "ASTERIA-CONFIG-010",
                message = "@AsteriaConfigChangeHandler class must implement ConfigChangeHandler.",
                symbol = declaration,
                reason = "The generated handler list is typed as ConfigChangeHandler<Receiver>.",
                fix = "Implement ConfigChangeHandler<YourReceiver> on this class.",
            )
            return null
        }
        val typeArg = implemented.arguments.singleOrNull()?.type?.resolve()
        if (typeArg?.declaration?.qualifiedName?.asString() != receiverType.declaration.qualifiedName?.asString()) {
            diagnostics.error(
                code = "ASTERIA-CONFIG-011",
                message = "Config change handler receiver type does not match the generated catalog.",
                symbol = declaration,
                reason = "This handler implements ConfigChangeHandler<${typeArg?.declaration?.qualifiedName?.asString()}>, but the catalog receiver is ${receiverType.declaration.qualifiedName?.asString()}.",
                fix = "Change the handler generic type or the @AsteriaConfigChangeCatalog receiverType so they match.",
            )
            return null
        }
        return ConfigChangeHandlerModel(declaration.toClassName())
    }

    private fun readTableModel(symbol: KSClassDeclaration): ConfigTableModel? {
        val annotation = symbol.findAnnotation(AsteriaConfigTable::class.qualifiedName!!) ?: run {
            diagnostics.error(
                code = "ASTERIA-CONFIG-023",
                message = "@AsteriaConfigTable annotation could not be resolved.",
                symbol = symbol,
                reason = "KSP returned the symbol for the annotation, but the processor could not read the annotation instance.",
                fix = "Check that the config annotation class is available on the KSP classpath.",
            )
            return null
        }
        val tableName = annotation.stringArg("name")
        val shape = AsteriaConfigTableShape.valueOf(annotation.enumArg("shape", AsteriaConfigTableShape.KEYED.name))
        val keyType = annotation.typeKSTypeArg("keyType")
            ?.takeUnless { it.isNothingType() }
            ?.toTypeName()
            .also { keyType ->
                if (shape == AsteriaConfigTableShape.KEYED && keyType == null) {
                    diagnostics.error(
                        code = "ASTERIA-CONFIG-012",
                        message = "Keyed config table requires keyType.",
                        symbol = symbol,
                        reason = "@AsteriaConfigTable(name = \"$tableName\", shape = KEYED) generates KeyedConfigTableRef<K, R>.",
                        fix = "Set keyType to the row key type, or change shape to LIST or SINGLETON.",
                    )
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
            diagnostics.error(
                code = "ASTERIA-CONFIG-013",
                message = "Config table tableType must resolve to a class.",
                symbol = symbol,
                reason = "The generated accessor stores the concrete table wrapper type.",
                fix = "Use a concrete ConfigTable implementation class in tableType.",
            )
            return null
        }
        val expectedSuperType = when (shape) {
            AsteriaConfigTableShape.KEYED -> "io.github.realmlabs.asteria.config.KeyedConfigTable"
            AsteriaConfigTableShape.LIST -> "io.github.realmlabs.asteria.config.ListConfigTable"
            AsteriaConfigTableShape.SINGLETON -> "io.github.realmlabs.asteria.config.SingleConfigTable"
        }
        if (!declaration.hasSuperType(expectedSuperType)) {
            diagnostics.error(
                code = "ASTERIA-CONFIG-014",
                message = "Config table tableType does not match table shape.",
                symbol = symbol,
                reason = "Table $tableName has shape=$shape, so tableType must extend $expectedSuperType.",
                fix = "Use a tableType matching the shape, or change the table shape.",
            )
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
            diagnostics.error(
                code = "ASTERIA-CONFIG-015",
                message = "Config table tableType has an unsupported type parameter count.",
                symbol = symbol,
                reason = "Table $tableName has shape=$shape, so tableType must declare either 0 or $expectedTypeArgumentCount type parameters.",
                fix = "Use a non-generic concrete table class, or declare the expected generic parameters.",
            )
            return null
        }
        return ConfigAccessorTableType(declaration.toClassName(), typeArgumentCount)
    }

    private fun validateTableModels(tables: List<ConfigTableModel>): Boolean {
        val duplicateTable = tables.groupBy { it.tableName }.filterValues { it.size > 1 }.keys.firstOrNull()
        if (duplicateTable != null) {
            diagnostics.error(
                code = "ASTERIA-CONFIG-016",
                message = "Duplicate config table name.",
                reason = "Config table names are runtime lookup keys and must be unique in one generated catalog.",
                fix = "Change one @AsteriaConfigTable(name = \"$duplicateTable\") to a unique table name.",
            )
            return false
        }
        val duplicateRef = tables.groupBy { it.refName }.filterValues { it.size > 1 }.keys.firstOrNull()
        if (duplicateRef != null) {
            diagnostics.error(
                code = "ASTERIA-CONFIG-017",
                message = "Duplicate generated config table ref name.",
                reason = "Generated table refs become properties on the same object.",
                fix = "Set a unique refName on one table that currently generates $duplicateRef.",
            )
            return false
        }
        val duplicateProperty = tables.groupBy { it.propertyName }.filterValues { it.size > 1 }.keys.firstOrNull()
        if (duplicateProperty != null) {
            diagnostics.error(
                code = "ASTERIA-CONFIG-018",
                message = "Duplicate generated config accessor property name.",
                reason = "Generated accessor properties share one snapshot/service extension namespace.",
                fix = "Set a unique propertyName on one table that currently generates $duplicateProperty.",
            )
            return false
        }
        return true
    }

    private fun validateConfigChangeHandlerModels(
        handlers: List<ConfigChangeHandlerModel>,
        symbol: KSClassDeclaration?,
    ): Boolean {
        val duplicate = handlers.groupBy { it.handlerType.canonicalName }
            .filterValues { it.size > 1 }
            .keys
            .firstOrNull()
        if (duplicate != null) {
            diagnostics.error(
                code = "ASTERIA-CONFIG-019",
                message = "Duplicate config change handler.",
                symbol = symbol,
                reason = "The generated handler list would instantiate $duplicate more than once.",
                fix = "Remove one duplicate annotation or split duplicated generated catalogs.",
            )
            return false
        }
        return true
    }

    private fun hasConfigChangeCatalog(resolver: Resolver): Boolean {
        return configChangeCatalogs(resolver).isNotEmpty()
    }

    private fun configChangeCatalogs(resolver: Resolver): List<KSClassDeclaration> {
        return resolver.getSymbolsWithAnnotation(AsteriaConfigChangeCatalog::class.qualifiedName!!)
            .map { symbol ->
                symbol.asAnnotatedClassOrInvalid(
                    diagnostics = diagnostics,
                    code = "ASTERIA-CONFIG-026",
                    annotationName = "@AsteriaConfigChangeCatalog",
                )
            }
            .toList()
            .successfulValues()
    }

    private fun KSClassDeclaration.findAnnotation(qualifiedName: String): KSAnnotation? {
        return annotations.firstOrNull { annotation ->
            annotation.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
        }
    }

    private fun KSClassDeclaration.requiredContainingFile(annotationName: String): KSFile? {
        val file = containingFile
        if (file != null) {
            return file
        }
        diagnostics.error(
            code = "ASTERIA-CONFIG-024",
            message = "$annotationName declaration must come from a source file.",
            symbol = this,
            reason = "Generated output needs source-file dependencies so KSP incremental builds cannot silently miss annotated declarations.",
            fix = "Move the annotation to a source declaration in this module.",
        )
        return null
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
