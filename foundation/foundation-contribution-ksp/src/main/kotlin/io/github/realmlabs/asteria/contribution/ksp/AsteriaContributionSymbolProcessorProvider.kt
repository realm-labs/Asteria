package io.github.realmlabs.asteria.contribution.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo
import io.github.realmlabs.asteria.ksp.*
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
    private var pendingDeferred: List<AsteriaKspDeferredSymbol> = emptyList()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val contributionSymbols = resolver.getSymbolsWithAnnotation(contributionAnnotationName).toList()
        val catalogSymbols = resolver.getSymbolsWithAnnotation(catalogAnnotationName).toList()

        val contributionReads = contributionSymbols.map { symbol ->
            val declaration = when (val target = symbol.asAnnotatedClassOrInvalid(
                diagnostics = diagnostics,
                code = "ASTERIA-CONTRIBUTION-012",
                annotationName = "@AsteriaContribution",
            )) {
                is AsteriaKspSymbolRead.Success -> target.value
                is AsteriaKspSymbolRead.Deferred -> return@map target
                AsteriaKspSymbolRead.Invalid -> return@map AsteriaKspSymbolRead.Invalid
            }
            readContributionModel(declaration)
        }
        val catalogReads = catalogSymbols.map { symbol ->
            val declaration = when (val target = symbol.asAnnotatedClassOrInvalid(
                diagnostics = diagnostics,
                code = "ASTERIA-CONTRIBUTION-013",
                annotationName = "@AsteriaContributionCatalog",
            )) {
                is AsteriaKspSymbolRead.Success -> target.value
                is AsteriaKspSymbolRead.Deferred -> return@map target
                AsteriaKspSymbolRead.Invalid -> return@map AsteriaKspSymbolRead.Invalid
            }
            readCatalogModel(declaration)
        }
        val deferred = (contributionReads + catalogReads).deferredSymbols()
        if (deferred.isNotEmpty()) {
            pendingDeferred = deferred
            return deferred.map { it.symbol }
        }
        pendingDeferred = emptyList()

        val catalogs = catalogReads.successfulValues()
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

        val contributions = contributionReads.successfulValues()
            .groupBy { it.contract.qualifiedName }

        (contributions.keys + catalogs.keys).forEach { contractName ->
            val contributionGroup = contributions[contractName].orEmpty()
            val catalog = catalogs[contractName].orEmpty().firstOrNull()
            val contract = catalog?.contract ?: contributionGroup.firstOrNull()?.contract ?: return@forEach
            val config = catalog?.toConfig() ?: defaultConfig(contract)
            val sourceFiles =
                (contributionGroup.mapNotNull { it.declaration.requiredContainingFile("@AsteriaContribution") } +
                        listOfNotNull(catalog?.declaration?.requiredContainingFile("@AsteriaContributionCatalog")))
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

    override fun finish() {
        diagnostics.reportUnprocessedDeferredSymbols(
            code = "ASTERIA-CONTRIBUTION-011",
            message = "Contribution symbol could not be processed after KSP rounds completed.",
            deferred = pendingDeferred,
            fix = "Check that the annotated class, its contribution contract, and its direct supertypes are resolvable in this module.",
        )
    }

    private fun readContributionModel(declaration: KSClassDeclaration): AsteriaKspSymbolRead<ContributionBinding> {
        val annotation = declaration.findAnnotation(contributionAnnotationName) ?: return AsteriaKspSymbolRead.Invalid
        val contract = when (val contractArg = annotation.classArg("contract", declaration)) {
            is ClassArgResult.Success -> contractArg.declaration
            is ClassArgResult.Deferred -> return AsteriaKspSymbolRead.Deferred(contractArg.deferred)
            ClassArgResult.Invalid -> {
                diagnostics.error(
                    code = "ASTERIA-CONTRIBUTION-002",
                    message = "@AsteriaContribution contract must be a class.",
                    symbol = declaration,
                    reason = "The generated contribution list is grouped by the contract KClass.",
                    fix = "Set contract to the interface or base class implemented by this contribution.",
                )
                return AsteriaKspSymbolRead.Invalid
            }
        }
        when (val validation = validateContributionDeclaration(declaration, contract)) {
            ContributionValidationResult.Valid -> Unit
            is ContributionValidationResult.Deferred -> return AsteriaKspSymbolRead.Deferred(validation.deferred)
            ContributionValidationResult.Invalid -> return AsteriaKspSymbolRead.Invalid
        }
        return AsteriaKspSymbolRead.Success(
            ContributionBinding(
                declaration = declaration,
                contract = contract.toContractModel(),
                contribution = ContributionModel(
                    implementationType = declaration.toClassName(),
                    objectDeclaration = declaration.classKind == ClassKind.OBJECT,
                    order = annotation.intArg("order"),
                ),
            ),
        )
    }

    private fun validateContributionDeclaration(
        declaration: KSClassDeclaration,
        contract: KSClassDeclaration,
    ): ContributionValidationResult {
        if (declaration.classKind != ClassKind.CLASS && declaration.classKind != ClassKind.OBJECT) {
            diagnostics.error(
                code = "ASTERIA-CONTRIBUTION-003",
                message = "@AsteriaContribution only supports classes and objects.",
                symbol = declaration,
                reason = "Generated lists contain either an object reference or a constructor call.",
                fix = "Move the annotation to a concrete class or object.",
            )
            return ContributionValidationResult.Invalid
        }
        if (Modifier.ABSTRACT in declaration.modifiers) {
            diagnostics.error(
                code = "ASTERIA-CONTRIBUTION-004",
                message = "Contribution must be concrete.",
                symbol = declaration,
                reason = "Abstract classes cannot be placed into the generated runtime list.",
                fix = "Annotate a concrete implementation instead of the abstract base type.",
            )
            return ContributionValidationResult.Invalid
        }
        if (declaration.getVisibility() != Visibility.PUBLIC) {
            diagnostics.error(
                code = "ASTERIA-CONTRIBUTION-005",
                message = "Contribution must be public.",
                symbol = declaration,
                reason = "The generated contribution catalog may be used from another package or module.",
                fix = "Make the contribution class/object public.",
            )
            return ContributionValidationResult.Invalid
        }
        if (declaration.typeParameters.isNotEmpty()) {
            diagnostics.error(
                code = "ASTERIA-CONTRIBUTION-006",
                message = "Contribution must not declare type parameters.",
                symbol = declaration,
                reason = "The generated catalog stores concrete implementation types and does not know which type arguments to use.",
                fix = "Create a non-generic implementation class for the desired contract.",
            )
            return ContributionValidationResult.Invalid
        }
        if (declaration.classKind == ClassKind.CLASS) {
            if (!declaration.hasPublicZeroArgumentConstructor()) {
                diagnostics.error(
                    code = "ASTERIA-CONTRIBUTION-007",
                    message = "Contribution class must have a public zero-argument constructor.",
                    symbol = declaration,
                    reason = "Contribution KSP only generates static lists; business code decides later how to index or wrap those instances.",
                    fix = "Add a public zero-argument constructor, or use an object contribution.",
                )
                return ContributionValidationResult.Invalid
            }
        }
        when (declaration.subtypeCheck(contract.qualifiedName?.asString().orEmpty())) {
            SubtypeCheckResult.Matches -> Unit
            SubtypeCheckResult.Deferred -> {
                return ContributionValidationResult.Deferred(
                    AsteriaKspDeferredSymbol(
                        symbol = declaration,
                        reason = "Contribution supertypes could not be resolved: ${declaration.qualifiedName?.asString()} -> ${contract.qualifiedName?.asString()}.",
                    ),
                )
            }
            SubtypeCheckResult.DoesNotMatch -> {
                diagnostics.error(
                    code = "ASTERIA-CONTRIBUTION-008",
                    message = "@AsteriaContribution declaration must implement its contract.",
                    symbol = declaration,
                    reason = "${declaration.qualifiedName?.asString()} is not a subtype of ${contract.qualifiedName?.asString()}.",
                    fix = "Implement the contract, or change the annotation contract to the intended interface/base class.",
                )
                return ContributionValidationResult.Invalid
            }
        }
        return ContributionValidationResult.Valid
    }

    private fun readCatalogModel(declaration: KSClassDeclaration): AsteriaKspSymbolRead<ContributionCatalogModel> {
        val annotation = declaration.findAnnotation(catalogAnnotationName) ?: return AsteriaKspSymbolRead.Invalid
        val contract = when (val contractArg = annotation.classArg("contract", declaration)) {
            is ClassArgResult.Success -> contractArg.declaration
            is ClassArgResult.Deferred -> return AsteriaKspSymbolRead.Deferred(contractArg.deferred)
            ClassArgResult.Invalid -> {
                diagnostics.error(
                    code = "ASTERIA-CONTRIBUTION-009",
                    message = "@AsteriaContributionCatalog contract must be a class.",
                    symbol = declaration,
                    reason = "The catalog customizes generated output for one contribution contract.",
                    fix = "Set contract to the interface or base class whose contribution list should be generated.",
                )
                return AsteriaKspSymbolRead.Invalid
            }
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
            return AsteriaKspSymbolRead.Invalid
        }
        return AsteriaKspSymbolRead.Success(
            ContributionCatalogModel(
                declaration = declaration,
                contract = contractModel,
                packageName = packageName,
                className = className,
                chunkSize = chunkSize,
            ),
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
        val simpleName = qualifiedName.substringAfterLast('.')
        return annotations.firstOrNull { annotation ->
            annotation.shortName.asString() == simpleName &&
                    annotation.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
        }
    }

    private fun KSClassDeclaration.requiredContainingFile(annotationName: String): KSFile? {
        val file = containingFile
        if (file != null) {
            return file
        }
        diagnostics.error(
            code = "ASTERIA-CONTRIBUTION-014",
            message = "$annotationName declaration must come from a source file.",
            symbol = this,
            reason = "Generated output needs source-file dependencies so KSP incremental builds cannot silently miss annotated declarations.",
            fix = "Move the annotation to a source declaration in this module.",
        )
        return null
    }

    private fun KSClassDeclaration.subtypeCheck(qualifiedName: String): SubtypeCheckResult {
        if (this.qualifiedName?.asString() == qualifiedName) {
            return SubtypeCheckResult.Matches
        }
        val matches = try {
            getAllSuperTypes().any { type ->
                (type.declaration as? KSClassDeclaration)?.qualifiedName?.asString() == qualifiedName
            }
        } catch (_: Throwable) {
            return SubtypeCheckResult.Deferred
        }
        return if (matches) SubtypeCheckResult.Matches else SubtypeCheckResult.DoesNotMatch
    }

    private fun KSClassDeclaration.hasPublicZeroArgumentConstructor(): Boolean {
        return getConstructors().any { constructor ->
            constructor.parameters.isEmpty() && constructor.getVisibility() == Visibility.PUBLIC
        }
    }

    private fun KSAnnotation.classArg(
        name: String,
        symbol: KSAnnotated,
    ): ClassArgResult {
        val type = arguments.firstOrNull { it.name?.asString() == name }?.value as? KSType
            ?: return ClassArgResult.Invalid
        val declaration = try {
            type.declaration
        } catch (error: Throwable) {
            return ClassArgResult.Deferred(
                AsteriaKspDeferredSymbol(
                    symbol = symbol,
                    reason = "Annotation argument $name could not be resolved: ${error.message ?: error::class.simpleName}",
                ),
            )
        }
        return when (declaration) {
            is KSClassDeclaration -> ClassArgResult.Success(declaration)
            else -> ClassArgResult.Invalid
        }
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

private sealed interface ClassArgResult {
    data class Success(val declaration: KSClassDeclaration) : ClassArgResult
    data class Deferred(val deferred: AsteriaKspDeferredSymbol) : ClassArgResult
    data object Invalid : ClassArgResult
}

private sealed interface ContributionValidationResult {
    data object Valid : ContributionValidationResult
    data class Deferred(val deferred: AsteriaKspDeferredSymbol) : ContributionValidationResult
    data object Invalid : ContributionValidationResult
}

private enum class SubtypeCheckResult {
    Matches,
    DoesNotMatch,
    Deferred,
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
