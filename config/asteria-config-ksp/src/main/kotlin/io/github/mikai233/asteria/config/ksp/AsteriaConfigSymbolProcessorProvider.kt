package io.github.mikai233.asteria.config.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import io.github.mikai233.asteria.config.annotations.AsteriaConfigCatalog
import io.github.mikai233.asteria.config.annotations.AsteriaConfigTable

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
        val invalid = tableSymbols.filterNot { it.validate() }
        if (invalid.isNotEmpty()) {
            return invalid
        }
        if (tableSymbols.isEmpty()) {
            generated = true
            return emptyList()
        }

        val config = readCodegenConfig(resolver)
        val tables = tableSymbols.mapNotNull(::readTableModel)
        val file = AsteriaConfigCodeGenerator.buildFile(config, tables)
        val sourceFiles = tableSymbols.mapNotNull { it.containingFile }.toTypedArray()
        file.writeTo(codeGenerator, Dependencies(aggregating = true, *sourceFiles))
        generated = true
        return emptyList()
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
                ?: "io.github.mikai233.asteria.generated.config",
            tablesObjectName = catalog?.stringArg("tablesObjectName")?.takeIf { it.isNotBlank() }
                ?: options["asteria.config.tables"]
                ?: "GameConfigTables",
            accessorClassName = catalog?.stringArg("accessorClassName")?.takeIf { it.isNotBlank() }
                ?: options["asteria.config.accessor"]
                ?: "GameConfigs",
        )
    }

    private fun readTableModel(symbol: KSClassDeclaration): ConfigTableModel? {
        val annotation = symbol.findAnnotation(AsteriaConfigTable::class.qualifiedName!!) ?: return null
        val tableName = annotation.stringArg("name")
        val keyType = annotation.typeArg("keyType")
        val explicitRowType = annotation.typeKSTypeArg("rowType")
        val rowType = if (explicitRowType == null || explicitRowType.isNothingType()) {
            symbol.asStarProjectedType().toTypeName()
        } else {
            explicitRowType.toTypeName()
        }
        return ConfigTableModel(
            tableName = tableName,
            keyType = keyType,
            rowType = rowType,
            refName = annotation.stringArg("refName").takeIf { it.isNotBlank() }
                ?: tableName.toUpperCamelIdentifier(),
            propertyName = annotation.stringArg("propertyName").takeIf { it.isNotBlank() }
                ?: tableName.toLowerCamelIdentifier(),
        )
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

    private fun KSAnnotation.typeArg(name: String): TypeName {
        return requireNotNull(typeKSTypeArg(name)) { "$name is required" }.toTypeName()
    }

    private fun KSAnnotation.typeKSTypeArg(name: String): KSType? {
        return arguments.firstOrNull { it.name?.asString() == name }?.value as? KSType
    }

    private fun KSType.isNothingType(): Boolean {
        return declaration.qualifiedName?.asString() == "kotlin.Nothing"
    }
}
