package io.github.realmlabs.asteria.contribution.ksp

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy

object AsteriaContributionCodeGenerator {
    fun buildFiles(
        config: ContributionCodegenConfig,
        contributions: List<ContributionModel>,
    ): List<ContributionGeneratedFile> {
        validateContributions(contributions)
        val sortedContributions = contributions.sortedWith(
            compareBy<ContributionModel> { it.order }.thenBy { it.implementationType.canonicalName },
        )
        val chunks = sortedContributions.chunked(config.chunkSize)
        if (chunks.size <= 1) {
            return listOf(ContributionGeneratedFile(config.className, buildSingleFile(config, sortedContributions)))
        }
        return listOf(ContributionGeneratedFile(config.className, buildAggregatorFile(config, chunks.size))) +
                chunks.mapIndexed { index, chunk ->
                    val chunkName = "${config.className}Chunk$index"
                    ContributionGeneratedFile(chunkName, buildChunkFile(config, chunkName, chunk))
                }
    }

    private fun buildSingleFile(
        config: ContributionCodegenConfig,
        contributions: List<ContributionModel>,
    ): FileSpec {
        return FileSpec.builder(config.packageName, config.className)
            .addType(buildCatalogType(config, buildContributionInitializer(contributions)))
            .build()
    }

    private fun buildAggregatorFile(
        config: ContributionCodegenConfig,
        chunkCount: Int,
    ): FileSpec {
        return FileSpec.builder(config.packageName, config.className)
            .addType(buildCatalogType(config, buildChunkAggregatorInitializer(config.className, chunkCount)))
            .build()
    }

    private fun buildChunkFile(
        config: ContributionCodegenConfig,
        chunkName: String,
        contributions: List<ContributionModel>,
    ): FileSpec {
        return FileSpec.builder(config.packageName, chunkName)
            .addType(
                TypeSpec.objectBuilder(chunkName)
                    .addModifiers(KModifier.INTERNAL)
                    .addProperty(
                        PropertySpec.builder("CONTRIBUTIONS", descriptorListType(config.contractType), KModifier.PUBLIC)
                            .initializer(buildContributionInitializer(contributions))
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun buildCatalogType(
        config: ContributionCodegenConfig,
        contributionsInitializer: CodeBlock,
    ): TypeSpec {
        return TypeSpec.objectBuilder(config.className)
            .addProperty(
                PropertySpec.builder("CONTRIBUTIONS", descriptorListType(config.contractType), KModifier.PUBLIC)
                    .initializer(contributionsInitializer)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("createAll")
                    .returns(listType(config.contractType))
                    .addStatement("return CONTRIBUTIONS.map { it.create() }")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("ALL", listType(config.contractType), KModifier.PUBLIC)
                    .initializer("createAll()")
                    .build(),
            )
            .build()
    }

    private fun validateContributions(contributions: List<ContributionModel>) {
        val duplicate = contributions.groupBy { it.implementationType.canonicalName }
            .filterValues { it.size > 1 }
            .keys
            .firstOrNull()
        require(duplicate == null) { "duplicate contribution $duplicate" }
    }

    private fun buildContributionInitializer(
        contributions: List<ContributionModel>,
    ): CodeBlock {
        if (contributions.isEmpty()) {
            return CodeBlock.of("emptyList()")
        }
        val builder = CodeBlock.builder()
        builder.add("listOf(\n")
        contributions.forEachIndexed { index, contribution ->
            builder.add(
                "    %T(implementationType = %T::class, order = %L, create = { ",
                CONTRIBUTION_DESCRIPTOR,
                contribution.implementationType,
                contribution.order,
            )
            if (contribution.objectDeclaration) {
                builder.add("%T", contribution.implementationType)
            } else {
                builder.add("%T()", contribution.implementationType)
            }
            builder.add(" })")
            if (index != contributions.lastIndex) {
                builder.add(",")
            }
            builder.add("\n")
        }
        builder.add(")")
        return builder.build()
    }

    private fun buildChunkAggregatorInitializer(
        className: String,
        chunkCount: Int,
    ): CodeBlock {
        val builder = CodeBlock.builder()
        builder.add("buildList {\n")
        builder.indent()
        repeat(chunkCount) { index ->
            builder.addStatement("addAll(%LChunk%L.CONTRIBUTIONS)", className, index)
        }
        builder.unindent()
        builder.add("}")
        return builder.build()
    }

    private fun descriptorListType(contractType: ClassName) =
        listType(CONTRIBUTION_DESCRIPTOR.parameterizedBy(contractType, WildcardTypeName.producerOf(contractType)))

    private fun listType(elementType: com.squareup.kotlinpoet.TypeName) = LIST.parameterizedBy(elementType)

    private val CONTRIBUTION_DESCRIPTOR = ClassName(
        "io.github.realmlabs.asteria.contribution",
        "AsteriaContributionDescriptor",
    )
    private val LIST = ClassName("kotlin.collections", "List")
}

data class ContributionGeneratedFile(
    val fileName: String,
    val file: FileSpec,
)

data class ContributionCodegenConfig(
    val packageName: String,
    val className: String,
    val contractType: ClassName,
    val chunkSize: Int = DEFAULT_CHUNK_SIZE,
) {
    init {
        require(packageName.isValidKotlinPackageName()) { "invalid generated contribution package name $packageName" }
        require(className.isValidKotlinIdentifier()) { "invalid generated contribution class name $className" }
        require(chunkSize > 0) { "generated contribution chunk size must be greater than zero" }
    }
}

data class ContributionModel(
    val implementationType: ClassName,
    val objectDeclaration: Boolean,
    val order: Int,
)

const val DEFAULT_CHUNK_SIZE: Int = 200

internal fun String.isValidKotlinIdentifier(): Boolean {
    if (isBlank()) {
        return false
    }
    val first = first()
    if (first != '_' && !first.isLetter()) {
        return false
    }
    return drop(1).all { it == '_' || it.isLetterOrDigit() }
}

internal fun String.isValidKotlinPackageName(): Boolean {
    return split('.').all { it.isValidKotlinIdentifier() }
}
