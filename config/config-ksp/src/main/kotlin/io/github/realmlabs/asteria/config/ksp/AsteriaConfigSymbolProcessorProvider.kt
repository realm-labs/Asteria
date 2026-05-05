package io.github.realmlabs.asteria.config.ksp

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import io.github.realmlabs.asteria.config.annotations.AsteriaConfigCatalog
import io.github.realmlabs.asteria.config.annotations.AsteriaConfigChangeCatalog
import io.github.realmlabs.asteria.config.annotations.AsteriaConfigChangeHandler
import io.github.realmlabs.asteria.config.annotations.AsteriaConfigTable
import io.github.realmlabs.asteria.config.annotations.AsteriaConfigTableShape

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
        if (tableSymbols.isEmpty() && handlerSymbols.isEmpty() && !hasConfigChangeCatalog(resolver)) {
            generated = true
            return emptyList()
        }

        if (tableSymbols.isNotEmpty()) {
            val config = readCodegenConfig(resolver)
            val tables = tableSymbols.mapNotNull(::readTableModel)
            val sourceFiles = tableSymbols.mapNotNull { it.containingFile }.toTypedArray()
            AsteriaConfigCodeGenerator.buildFiles(config, tables).forEach { generated ->
                generated.file.writeTo(codeGenerator, Dependencies(aggregating = true, *sourceFiles))
            }
        }
        if (handlerSymbols.isNotEmpty() || hasConfigChangeCatalog(resolver)) {
            generateConfigChangeHandlers(resolver, handlerSymbols)
        }
        generated = true
        return emptyList()
    }

    private fun generateConfigChangeHandlers(
        resolver: Resolver,
        handlerSymbols: List<KSClassDeclaration>,
    ) {
        val processorConfig = readConfigChangeCodegenConfig(resolver) ?: return
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
