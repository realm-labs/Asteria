package io.github.realmlabs.asteria.config.ksp

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec

object AsteriaConfigValidatorCodeGenerator {
    fun buildFiles(
        config: ConfigValidatorCodegenConfig,
        validators: List<ConfigValidatorModel>,
    ): List<ConfigValidatorGeneratedFile> {
        validateValidators(validators)
        val sortedValidators = validators.sortedBy { it.validatorType.canonicalName }
        val chunks = sortedValidators.chunked(VALIDATOR_CHUNK_SIZE)
        if (chunks.size <= 1) {
            return listOf(ConfigValidatorGeneratedFile(config.className, buildSingleFile(config, sortedValidators)))
        }
        return listOf(ConfigValidatorGeneratedFile(config.className, buildAggregatorFile(config, chunks.size))) +
                chunks.mapIndexed { index, chunk ->
                    val chunkName = "${config.className}Chunk$index"
                    ConfigValidatorGeneratedFile(chunkName, buildChunkFile(config, chunkName, chunk))
                }
    }

    fun buildFile(
        config: ConfigValidatorCodegenConfig,
        validators: List<ConfigValidatorModel>,
    ): FileSpec {
        return buildFiles(config, validators).first { it.fileName == config.className }.file
    }

    private fun buildSingleFile(
        config: ConfigValidatorCodegenConfig,
        validators: List<ConfigValidatorModel>,
    ): FileSpec {
        return FileSpec.builder(config.packageName, config.className)
            .addType(
                TypeSpec.objectBuilder(config.className)
                    .addProperty(
                        PropertySpec.builder("ALL", VALIDATOR_LIST, KModifier.PUBLIC)
                            .initializer(buildInitializer(validators))
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun buildAggregatorFile(
        config: ConfigValidatorCodegenConfig,
        chunkCount: Int,
    ): FileSpec {
        return FileSpec.builder(config.packageName, config.className)
            .addType(
                TypeSpec.objectBuilder(config.className)
                    .addProperty(
                        PropertySpec.builder("ALL", VALIDATOR_LIST, KModifier.PUBLIC)
                            .initializer(buildChunkAggregatorInitializer(config.className, chunkCount))
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun buildChunkFile(
        config: ConfigValidatorCodegenConfig,
        chunkName: String,
        validators: List<ConfigValidatorModel>,
    ): FileSpec {
        return FileSpec.builder(config.packageName, chunkName)
            .addType(
                TypeSpec.objectBuilder(chunkName)
                    .addModifiers(KModifier.INTERNAL)
                    .addProperty(
                        PropertySpec.builder("ALL", VALIDATOR_LIST, KModifier.PUBLIC)
                            .initializer(buildInitializer(validators))
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun validateValidators(validators: List<ConfigValidatorModel>) {
        val duplicate = validators.groupBy { it.validatorType.canonicalName }.filterValues { it.size > 1 }
            .keys
            .firstOrNull()
        require(duplicate == null) { "duplicate config validator $duplicate" }
    }

    private fun buildInitializer(validators: List<ConfigValidatorModel>): CodeBlock {
        if (validators.isEmpty()) {
            return CodeBlock.of("emptyList()")
        }
        val builder = CodeBlock.builder()
        builder.add("listOf(\n")
        validators.sortedBy { it.validatorType.canonicalName }.forEachIndexed { index, validator ->
            if (validator.objectDeclaration) {
                builder.add("    %T", validator.validatorType)
            } else {
                builder.add("    %T()", validator.validatorType)
            }
            if (index != validators.lastIndex) {
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
            builder.addStatement("addAll(%LChunk%L.ALL)", className, index)
        }
        builder.unindent()
        builder.add("}")
        return builder.build()
    }

    private const val VALIDATOR_CHUNK_SIZE = 200
    private val CONFIG_VALIDATOR = ClassName("io.github.realmlabs.asteria.config", "ConfigValidator")
    private val LIST = ClassName("kotlin.collections", "List")
    private val VALIDATOR_LIST = LIST.parameterizedBy(CONFIG_VALIDATOR)
}

data class ConfigValidatorGeneratedFile(
    val fileName: String,
    val file: FileSpec,
)

data class ConfigValidatorCodegenConfig(
    val packageName: String,
    val className: String,
) {
    init {
        require(packageName.isNotBlank()) { "generated config validator package name must not be blank" }
        require(className.isValidKotlinIdentifier()) { "invalid generated config validator class name $className" }
    }
}

data class ConfigValidatorModel(
    val validatorType: ClassName,
    val objectDeclaration: Boolean,
)
