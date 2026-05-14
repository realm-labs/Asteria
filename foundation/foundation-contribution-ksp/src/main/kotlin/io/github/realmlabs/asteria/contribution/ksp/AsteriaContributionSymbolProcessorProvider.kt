package io.github.realmlabs.asteria.contribution.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo
import io.github.realmlabs.asteria.ksp.AsteriaKspDiagnostics
import java.util.*

class AsteriaContributionSymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return AsteriaContributionSymbolProcessor(environment.codeGenerator, environment.logger)
    }
}

private class AsteriaContributionSymbolProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    private val diagnostics = AsteriaKspDiagnostics(logger, "contribution-ksp")
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
            diagnostics.error(
                code = "ASTERIA-CONTRIBUTION-001",
                message = "Only one @AsteriaContributionCatalog is allowed for a contract.",
                symbol = duplicateCatalogs.drop(1).first().declaration,
                reason = "A catalog controls the generated object name, package, and chunk size for one contract; multiple catalogs would generate conflicting outputs.",
                fix = "Keep one catalog for $contractName and remove or merge the others.",
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
            diagnostics.error(
                code = "ASTERIA-CONTRIBUTION-002",
                message = "@AsteriaContribution contract must be a class.",
                symbol = declaration,
                reason = "The generated contribution list is grouped by the contract KClass.",
                fix = "Set contract to the interface or base class implemented by this contribution.",
            )
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
            diagnostics.error(
                code = "ASTERIA-CONTRIBUTION-003",
                message = "@AsteriaContribution only supports classes and objects.",
                symbol = declaration,
                reason = "Generated lists contain either an object reference or a constructor call.",
                fix = "Move the annotation to a concrete class or object.",
            )
            return false
        }
        if (Modifier.ABSTRACT in declaration.modifiers) {
            diagnostics.error(
                code = "ASTERIA-CONTRIBUTION-004",
                message = "Contribution must be concrete.",
                symbol = declaration,
                reason = "Abstract classes cannot be placed into the generated runtime list.",
                fix = "Annotate a concrete implementation instead of the abstract base type.",
            )
            return false
        }
        if (declaration.getVisibility() != Visibility.PUBLIC) {
            diagnostics.error(
                code = "ASTERIA-CONTRIBUTION-005",
                message = "Contribution must be public.",
                symbol = declaration,
                reason = "The generated contribution catalog may be used from another package or module.",
                fix = "Make the contribution class/object public.",
            )
            return false
        }
        if (declaration.typeParameters.isNotEmpty()) {
            diagnostics.error(
                code = "ASTERIA-CONTRIBUTION-006",
                message = "Contribution must not declare type parameters.",
                symbol = declaration,
                reason = "The generated catalog stores concrete implementation types and does not know which type arguments to use.",
                fix = "Create a non-generic implementation class for the desired contract.",
            )
            return false
        }
        if (declaration.classKind == ClassKind.CLASS) {
            if (declaration.getConstructors().none { it.parameters.isEmpty() }) {
                diagnostics.error(
                    code = "ASTERIA-CONTRIBUTION-007",
                    message = "Contribution class must have a zero-argument constructor.",
                    symbol = declaration,
                    reason = "Contribution KSP only generates static lists; business code decides later how to index or wrap those instances.",
                    fix = "Add a public zero-argument constructor, or use an object contribution.",
                )
                return false
            }
        }
        if (!declaration.isSubtypeOf(contract.qualifiedName?.asString().orEmpty())) {
            diagnostics.error(
                code = "ASTERIA-CONTRIBUTION-008",
                message = "@AsteriaContribution declaration must implement its contract.",
                symbol = declaration,
                reason = "${declaration.qualifiedName?.asString()} is not a subtype of ${contract.qualifiedName?.asString()}.",
                fix = "Implement the contract, or change the annotation contract to the intended interface/base class.",
            )
            return false
        }
        return true
    }

    private fun readCatalogModel(declaration: KSClassDeclaration): ContributionCatalogModel? {
        val annotation = declaration.findAnnotation(catalogAnnotationName) ?: return null
        val contract = annotation.classArg("contract") ?: run {
            diagnostics.error(
                code = "ASTERIA-CONTRIBUTION-009",
                message = "@AsteriaContributionCatalog contract must be a class.",
                symbol = declaration,
                reason = "The catalog customizes generated output for one contribution contract.",
                fix = "Set contract to the interface or base class whose contribution list should be generated.",
            )
            return null
        }
        val contractModel = contract.toContractModel()
        val packageName = annotation.stringArg("packageName")
            .ifBlank { contractModel.packageName }
        val className = annotation.stringArg("className")
            .ifBlank { "Generated${contract.simpleName.asString().toContributionTypeNamePart()}Contributions" }
        val chunkSize = annotation.intArg("chunkSize", default = DEFAULT_CHUNK_SIZE)
        if (chunkSize <= 0) {
            diagnostics.error(
                code = "ASTERIA-CONTRIBUTION-010",
                message = "Contribution catalog chunkSize must be greater than zero.",
                symbol = declaration,
                reason = "KSP splits large generated lists into positive-size chunks to avoid oversized generated methods.",
                fix = "Remove chunkSize to use the default, or set it to a positive integer.",
            )
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
            className = "Generated${contract.simpleName.toContributionTypeNamePart()}Contributions",
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

}
internal fun String.toContributionTypeNamePart(): String {
    val tokens = split(Regex("[^A-Za-z0-9]+")).filter { it.isNotBlank() }.ifEmpty { listOf("Default") }
    return tokens.joinToString("") { token ->
        token.replaceFirstChar { char ->
            if (char.isLowerCase()) {
                char.titlecase(Locale.getDefault())
            } else {
                char.toString()
            }
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
