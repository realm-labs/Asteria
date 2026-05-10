package io.github.realmlabs.asteria.contribution.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo
import java.util.Locale

class AsteriaContributionSymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return AsteriaContributionSymbolProcessor(environment.codeGenerator, environment.logger)
    }
}

private class AsteriaContributionSymbolProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    private val contributionAnnotationName = "io.github.realmlabs.asteria.contribution.AsteriaContribution"
    private val catalogAnnotationName = "io.github.realmlabs.asteria.contribution.AsteriaContributionCatalog"

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val contributionSymbols = resolver.getSymbolsWithAnnotation(contributionAnnotationName).toList()
        val catalogSymbols = resolver.getSymbolsWithAnnotation(catalogAnnotationName).toList()
        val deferred = (contributionSymbols + catalogSymbols).filterNot { it.validate() }
        if (deferred.isNotEmpty()) {
            return deferred
        }

        val catalogs = catalogSymbols
            .filterIsInstance<KSClassDeclaration>()
            .mapNotNull { readCatalogModel(it) }
            .groupBy { it.contract.qualifiedName }

        catalogs.filterValues { it.size > 1 }.forEach { (contractName, duplicateCatalogs) ->
            logger.error(
                "only one @AsteriaContributionCatalog is allowed for contract $contractName",
                duplicateCatalogs.drop(1).first().declaration,
            )
        }

        val contributions = contributionSymbols
            .filterIsInstance<KSClassDeclaration>()
            .mapNotNull { readContributionModel(it) }
            .groupBy { it.contract.qualifiedName }

        (contributions.keys + catalogs.keys).forEach { contractName ->
            val contributionGroup = contributions[contractName].orEmpty()
            val catalog = catalogs[contractName].orEmpty().firstOrNull()
            val contract = catalog?.contract ?: contributionGroup.firstOrNull()?.contract ?: return@forEach
            val config = catalog?.toConfig() ?: defaultConfig(contract)
            val sourceFiles = (contributionGroup.mapNotNull { it.declaration.containingFile } +
                    listOfNotNull(catalog?.declaration?.containingFile))
                .distinctBy { it.filePath }
                .toTypedArray()
            AsteriaContributionCodeGenerator.buildFiles(
                config = config,
                contributions = contributionGroup.map { it.contribution },
            ).forEach { generated ->
                generated.file.writeTo(codeGenerator, Dependencies(aggregating = true, *sourceFiles))
            }
        }

        return emptyList()
    }

    private fun readContributionModel(declaration: KSClassDeclaration): ContributionBinding? {
        val annotation = declaration.findAnnotation(contributionAnnotationName) ?: return null
        val contract = annotation.classArg("contract") ?: run {
            logger.error("@AsteriaContribution contract must be a class", declaration)
            return null
        }
        if (!validateContributionDeclaration(declaration, contract)) {
            return null
        }
        return ContributionBinding(
            declaration = declaration,
            contract = contract.toContractModel(),
            contribution = ContributionModel(
                implementationType = declaration.toClassName(),
                objectDeclaration = declaration.classKind == ClassKind.OBJECT,
                order = annotation.intArg("order"),
            ),
        )
    }

    private fun validateContributionDeclaration(
        declaration: KSClassDeclaration,
        contract: KSClassDeclaration,
    ): Boolean {
        if (declaration.classKind != ClassKind.CLASS && declaration.classKind != ClassKind.OBJECT) {
            logger.error("@AsteriaContribution only supports classes and objects", declaration)
            return false
        }
        if (Modifier.ABSTRACT in declaration.modifiers) {
            logger.error("contribution must be concrete", declaration)
            return false
        }
        if (declaration.getVisibility() != Visibility.PUBLIC) {
            logger.error("contribution must be public", declaration)
            return false
        }
        if (declaration.typeParameters.isNotEmpty()) {
            logger.error("contribution must not declare type parameters", declaration)
            return false
        }
        if (declaration.classKind == ClassKind.CLASS) {
            if (declaration.getConstructors().none { it.parameters.isEmpty() }) {
                logger.error("contribution class must have a zero-argument constructor", declaration)
                return false
            }
        }
        if (!declaration.isSubtypeOf(contract.qualifiedName?.asString().orEmpty())) {
            logger.error(
                "@AsteriaContribution declaration must implement ${contract.qualifiedName?.asString()}",
                declaration,
            )
            return false
        }
        return true
    }

    private fun readCatalogModel(declaration: KSClassDeclaration): ContributionCatalogModel? {
        val annotation = declaration.findAnnotation(catalogAnnotationName) ?: return null
        val contract = annotation.classArg("contract") ?: run {
            logger.error("@AsteriaContributionCatalog contract must be a class", declaration)
            return null
        }
        val contractModel = contract.toContractModel()
        val packageName = annotation.stringArg("packageName")
            .ifBlank { contractModel.packageName }
        val className = annotation.stringArg("className")
            .ifBlank { "Generated${contract.simpleName.asString().toTypeNamePart()}Contributions" }
        val chunkSize = annotation.intArg("chunkSize", default = DEFAULT_CHUNK_SIZE)
        if (chunkSize <= 0) {
            logger.error("contribution catalog chunkSize must be greater than zero", declaration)
            return null
        }
        return ContributionCatalogModel(
            declaration = declaration,
            contract = contractModel,
            packageName = packageName,
            className = className,
            chunkSize = chunkSize,
        )
    }

    private fun defaultConfig(contract: ContractModel): ContributionCodegenConfig {
        return ContributionCodegenConfig(
            packageName = contract.packageName,
            className = "Generated${contract.simpleName.toTypeNamePart()}Contributions",
            contractType = contract.type,
        )
    }

    private fun ContributionCatalogModel.toConfig(): ContributionCodegenConfig {
        return ContributionCodegenConfig(
            packageName = packageName,
            className = className,
            contractType = contract.type,
            chunkSize = chunkSize,
        )
    }

    private fun KSClassDeclaration.toContractModel(): ContractModel {
        val qualifiedName = qualifiedName?.asString().orEmpty()
        val simpleName = simpleName.asString()
        val packageName = packageName.asString()
        return ContractModel(
            qualifiedName = qualifiedName,
            packageName = packageName,
            simpleName = simpleName,
            type = toClassName(),
        )
    }

    private fun KSClassDeclaration.findAnnotation(qualifiedName: String): KSAnnotation? {
        return annotations.firstOrNull { annotation ->
            annotation.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
        }
    }

    private fun KSClassDeclaration.isSubtypeOf(qualifiedName: String): Boolean {
        if (this.qualifiedName?.asString() == qualifiedName) {
            return true
        }
        return getAllSuperTypes().any { type ->
            (type.declaration as? KSClassDeclaration)?.qualifiedName?.asString() == qualifiedName
        }
    }

    private fun KSAnnotation.classArg(name: String): KSClassDeclaration? {
        val type = arguments.firstOrNull { it.name?.asString() == name }?.value as? KSType
        return type?.declaration as? KSClassDeclaration
    }

    private fun KSAnnotation.stringArg(name: String): String {
        return arguments.firstOrNull { it.name?.asString() == name }?.value as? String ?: ""
    }

    private fun KSAnnotation.intArg(
        name: String,
        default: Int = 0,
    ): Int {
        return arguments.firstOrNull { it.name?.asString() == name }?.value as? Int ?: default
    }

    private fun String.toTypeNamePart(): String {
        val tokens = split(Regex("[^A-Za-z0-9]+")).filter { it.isNotBlank() }.ifEmpty { listOf("Default") }
        return tokens.joinToString("") { token ->
            token.lowercase(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }
    }
}

private data class ContributionBinding(
    val declaration: KSClassDeclaration,
    val contract: ContractModel,
    val contribution: ContributionModel,
)

private data class ContributionCatalogModel(
    val declaration: KSClassDeclaration,
    val contract: ContractModel,
    val packageName: String,
    val className: String,
    val chunkSize: Int,
)

private data class ContractModel(
    val qualifiedName: String,
    val packageName: String,
    val simpleName: String,
    val type: ClassName,
)
