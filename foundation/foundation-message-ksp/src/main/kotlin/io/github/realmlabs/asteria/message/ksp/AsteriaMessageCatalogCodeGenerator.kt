package io.github.realmlabs.asteria.message.ksp

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import io.github.realmlabs.asteria.message.MessageCatalog
import io.github.realmlabs.asteria.message.MessageCatalogEntry

internal object AsteriaMessageCatalogCodeGenerator {
    fun buildFiles(
        generatedPackage: String,
        typeNamePart: String,
        bindings: List<MessageCatalogBindingModel>,
    ): List<MessageCatalogGeneratedFile> {
        val catalogObjectName = "Generated${typeNamePart}MessageCatalog"
        val sortedBindings = bindings.sortedWith(compareBy({ it.dispatcher }, { it.messageClassName.canonicalName }))
        val chunks = sortedBindings.chunked(CATALOG_CHUNK_SIZE)
        val mainFile = MessageCatalogGeneratedFile(
            fileName = catalogObjectName,
            file = buildCatalogFile(generatedPackage, catalogObjectName, sortedBindings, chunks.size),
        )
        if (chunks.size <= 1) {
            return listOf(mainFile)
        }
        val chunkFiles = chunks.mapIndexed { index, chunk ->
            val chunkObjectName = "${catalogObjectName}Chunk$index"
            MessageCatalogGeneratedFile(
                fileName = chunkObjectName,
                file = buildChunkFile(generatedPackage, chunkObjectName, chunk),
            )
        }
        return listOf(mainFile) + chunkFiles
    }

    private fun buildCatalogFile(
        generatedPackage: String,
        catalogObjectName: String,
        sortedBindings: List<MessageCatalogBindingModel>,
        chunkCount: Int,
    ): FileSpec {
        return FileSpec.builder(generatedPackage, catalogObjectName)
            .addType(
                TypeSpec.objectBuilder(catalogObjectName)
                    .addSuperinterface(MessageCatalog::class)
                    .addProperty(
                        PropertySpec.builder("bindings", MESSAGE_CATALOG_ENTRY_LIST)
                            .addModifiers(KModifier.OVERRIDE)
                            .initializer(
                                if (chunkCount <= 1) {
                                    buildCatalogInitializer(sortedBindings)
                                } else {
                                    buildCatalogAggregatorInitializer(generatedPackage, catalogObjectName, chunkCount)
                                },
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun buildChunkFile(
        generatedPackage: String,
        chunkObjectName: String,
        bindings: List<MessageCatalogBindingModel>,
    ): FileSpec {
        return FileSpec.builder(generatedPackage, chunkObjectName)
            .addType(
                TypeSpec.objectBuilder(chunkObjectName)
                    .addModifiers(KModifier.INTERNAL)
                    .addProperty(
                        PropertySpec.builder("bindings", MESSAGE_CATALOG_ENTRY_LIST)
                            .initializer(buildCatalogInitializer(bindings))
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun buildCatalogAggregatorInitializer(
        generatedPackage: String,
        catalogObjectName: String,
        chunkCount: Int,
    ): CodeBlock {
        val builder = CodeBlock.builder()
        builder.add("buildList {\n")
        repeat(chunkCount) { index ->
            builder.add("  addAll(%T.bindings)\n", ClassName(generatedPackage, "${catalogObjectName}Chunk$index"))
        }
        builder.add("}")
        return builder.build()
    }

    private fun buildCatalogInitializer(bindings: List<MessageCatalogBindingModel>): CodeBlock {
        val builder = CodeBlock.builder()
        builder.add("listOf(\n")
        bindings.forEachIndexed { index, binding ->
            builder.add(
                "  %T(\n    messageClass = %T::class,\n    handlerClass = %T::class,\n    dispatcher = %S,\n  )",
                MessageCatalogEntry::class,
                binding.messageClassName,
                binding.handlerClassName,
                binding.dispatcher,
            )
            if (index != bindings.lastIndex) {
                builder.add(",\n")
            } else {
                builder.add("\n")
            }
        }
        builder.add(")")
        return builder.build()
    }

    private val MESSAGE_CATALOG_ENTRY_LIST = List::class.asClassName()
        .parameterizedBy(MessageCatalogEntry::class.asClassName())
    private const val CATALOG_CHUNK_SIZE = 200
}

internal data class MessageCatalogGeneratedFile(
    val fileName: String,
    val file: FileSpec,
)

internal data class MessageCatalogBindingModel(
    val messageClassName: ClassName,
    val handlerClassName: ClassName,
    val dispatcher: String,
)
