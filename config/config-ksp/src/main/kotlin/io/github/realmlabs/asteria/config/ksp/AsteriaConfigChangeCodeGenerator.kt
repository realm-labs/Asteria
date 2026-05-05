package io.github.realmlabs.asteria.config.ksp

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec

object AsteriaConfigChangeCodeGenerator {
    fun buildFiles(
        config: ConfigChangeCodegenConfig,
        handlers: List<ConfigChangeHandlerModel>,
    ): List<ConfigChangeGeneratedFile> {
        validateHandlers(handlers)
        val sortedHandlers = handlers.sortedBy { it.handlerType.canonicalName }
        val chunks = sortedHandlers.chunked(HANDLER_CHUNK_SIZE)
        if (chunks.size <= 1) {
            return listOf(ConfigChangeGeneratedFile(config.className, buildSingleFile(config, sortedHandlers)))
        }
        return listOf(ConfigChangeGeneratedFile(config.className, buildAggregatorFile(config, chunks.size))) +
                chunks.mapIndexed { index, chunk ->
                    val chunkName = "${config.className}Chunk$index"
                    ConfigChangeGeneratedFile(chunkName, buildChunkFile(config, chunkName, chunk))
                }
    }

    fun buildFile(
        config: ConfigChangeCodegenConfig,
        handlers: List<ConfigChangeHandlerModel>,
    ): FileSpec {
        return buildFiles(config, handlers).first { it.fileName == config.className }.file
    }

    private fun buildSingleFile(
        config: ConfigChangeCodegenConfig,
        handlers: List<ConfigChangeHandlerModel>,
    ): FileSpec {
        val handlerType = CONFIG_CHANGE_HANDLER.parameterizedBy(config.receiverType)
        val listType = LIST.parameterizedBy(handlerType)
        return FileSpec.builder(config.packageName, config.className)
            .addType(
                TypeSpec.objectBuilder(config.className)
                    .addProperty(
                        PropertySpec.builder("ALL", listType, KModifier.PUBLIC)
                            .initializer(buildInitializer(handlers))
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun buildAggregatorFile(
        config: ConfigChangeCodegenConfig,
        chunkCount: Int,
    ): FileSpec {
        val handlerType = CONFIG_CHANGE_HANDLER.parameterizedBy(config.receiverType)
        val listType = LIST.parameterizedBy(handlerType)
        return FileSpec.builder(config.packageName, config.className)
            .addType(
                TypeSpec.objectBuilder(config.className)
                    .addProperty(
                        PropertySpec.builder("ALL", listType, KModifier.PUBLIC)
                            .initializer(buildChunkAggregatorInitializer(config.className, chunkCount))
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun buildChunkFile(
        config: ConfigChangeCodegenConfig,
        chunkName: String,
        handlers: List<ConfigChangeHandlerModel>,
    ): FileSpec {
        val handlerType = CONFIG_CHANGE_HANDLER.parameterizedBy(config.receiverType)
        val listType = LIST.parameterizedBy(handlerType)
        return FileSpec.builder(config.packageName, chunkName)
            .addType(
                TypeSpec.objectBuilder(chunkName)
                    .addModifiers(KModifier.INTERNAL)
                    .addProperty(
                        PropertySpec.builder("ALL", listType, KModifier.PUBLIC)
                            .initializer(buildInitializer(handlers))
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun validateHandlers(handlers: List<ConfigChangeHandlerModel>) {
        val duplicate = handlers.groupBy { it.handlerType.canonicalName }.filterValues { it.size > 1 }.keys.firstOrNull()
        require(duplicate == null) { "duplicate config change handler $duplicate" }
    }

    private fun buildInitializer(handlers: List<ConfigChangeHandlerModel>): CodeBlock {
        if (handlers.isEmpty()) {
            return CodeBlock.of("emptyList()")
        }
        val builder = CodeBlock.builder()
        builder.add("listOf(\n")
        handlers.sortedBy { it.handlerType.canonicalName }.forEachIndexed { index, handler ->
            builder.add("    %T()", handler.handlerType)
            if (index != handlers.lastIndex) {
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

    private const val HANDLER_CHUNK_SIZE = 200
    private val CONFIG_CHANGE_HANDLER = ClassName("io.github.realmlabs.asteria.config", "ConfigChangeHandler")
    private val LIST = ClassName("kotlin.collections", "List")
}

data class ConfigChangeGeneratedFile(
    val fileName: String,
    val file: FileSpec,
)

data class ConfigChangeCodegenConfig(
    val packageName: String,
    val className: String,
    val receiverType: TypeName,
) {
    init {
        require(packageName.isNotBlank()) { "generated config change package name must not be blank" }
        require(className.isValidKotlinIdentifier()) { "invalid generated config change class name $className" }
    }
}

data class ConfigChangeHandlerModel(
    val handlerType: ClassName,
)
