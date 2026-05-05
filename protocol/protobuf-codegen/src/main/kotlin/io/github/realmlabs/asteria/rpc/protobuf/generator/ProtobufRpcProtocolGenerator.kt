package io.github.realmlabs.asteria.rpc.protobuf.generator

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.protobuf.DescriptorProtos
import com.google.protobuf.ExtensionRegistry
import com.squareup.kotlinpoet.*
import io.github.realmlabs.asteria.rpc.protobuf.AsteriaRpcOptionsProto
import io.github.realmlabs.asteria.rpc.protobuf.GeneratedProtobufRpcProtocol
import io.github.realmlabs.asteria.rpc.protobuf.ProtobufRpcProtocolBuilder
import io.github.realmlabs.asteria.rpc.protobuf.ProtobufRpcProtocolContributor
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.writeText

object ProtobufRpcProtocolGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        generate(ProtobufRpcGeneratorConfig.parse(args))
    }

    fun generate(config: ProtobufRpcGeneratorConfig) {
        val metadata = readMetadata(config.metadata)
        val entityIds = config.descriptorSet?.let(::readEntityIds).orEmpty()
        buildFiles(config, metadata, entityIds).forEach { generated ->
            generated.file.writeTo(config.kotlinOutput)
        }
        writeServiceProvider(config)
    }

    fun buildFiles(
        config: ProtobufRpcGeneratorConfig,
        metadata: RpcProtocolMetadata,
    ): List<GeneratedRpcProtocolFile> {
        return buildFiles(config, metadata, emptyList())
    }

    fun buildFile(
        config: ProtobufRpcGeneratorConfig,
        metadata: RpcProtocolMetadata,
    ): FileSpec {
        return buildFiles(config, metadata).first { it.fileName == config.className }.file
    }

    private fun buildFiles(
        config: ProtobufRpcGeneratorConfig,
        metadata: RpcProtocolMetadata,
        entityIds: List<MessageEntityId>,
    ): List<GeneratedRpcProtocolFile> {
        validateMetadata(metadata)
        entityIds.forEach(::validateField)
        if (metadata.messages.size + entityIds.size <= CONTRIBUTION_CHUNK_SIZE) {
            return listOf(GeneratedRpcProtocolFile(config.className, buildSingleFile(config, metadata, entityIds)))
        }
        val messageChunks = metadata.messages.sortedBy { it.id }.chunked(CONTRIBUTION_CHUNK_SIZE)
        val entityIdChunks = entityIds.chunked(CONTRIBUTION_CHUNK_SIZE)
        var chunkIndex = 0
        val chunks = messageChunks.map { chunk ->
            val chunkName = "${config.className}Chunk${chunkIndex++}"
            GeneratedRpcProtocolFile(chunkName, buildMessageChunkFile(config, chunkName, chunk))
        } + entityIdChunks.map { chunk ->
            val chunkName = "${config.className}Chunk${chunkIndex++}"
            GeneratedRpcProtocolFile(chunkName, buildEntityIdChunkFile(config, chunkName, chunk))
        }
        return listOf(GeneratedRpcProtocolFile(config.className, buildAggregatorFile(config, chunks.map { it.fileName }))) +
                chunks
    }

    private fun buildSingleFile(
        config: ProtobufRpcGeneratorConfig,
        metadata: RpcProtocolMetadata,
        entityIds: List<MessageEntityId>,
    ): FileSpec {
        val contributeFunction = FunSpec.builder("contribute")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("builder", ProtobufRpcProtocolBuilder::class)
            .addCode(buildContributorCode(metadata, entityIds))
            .build()
        val type = TypeSpec.classBuilder(config.className)
            .superclass(GeneratedProtobufRpcProtocol::class)
            .addFunction(contributeFunction)
            .build()
        return FileSpec.builder(config.packageName, config.className)
            .addType(type)
            .build()
    }

    private fun buildAggregatorFile(
        config: ProtobufRpcGeneratorConfig,
        chunkNames: List<String>,
    ): FileSpec {
        val contributeFunction = FunSpec.builder("contribute")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("builder", ProtobufRpcProtocolBuilder::class)
            .addCode(buildChunkAggregatorCode(chunkNames))
            .build()
        val type = TypeSpec.classBuilder(config.className)
            .superclass(GeneratedProtobufRpcProtocol::class)
            .addFunction(contributeFunction)
            .build()
        return FileSpec.builder(config.packageName, config.className)
            .addType(type)
            .build()
    }

    private fun buildMessageChunkFile(
        config: ProtobufRpcGeneratorConfig,
        chunkName: String,
        messages: List<RpcMessageSpec>,
    ): FileSpec {
        val type = TypeSpec.objectBuilder(chunkName)
            .addModifiers(KModifier.INTERNAL)
            .addFunction(
                FunSpec.builder("contribute")
                    .addParameter("builder", ProtobufRpcProtocolBuilder::class)
                    .addCode(buildMessageContributorCode(messages))
                    .build(),
            )
            .build()
        return FileSpec.builder(config.packageName, chunkName)
            .addType(type)
            .build()
    }

    private fun buildEntityIdChunkFile(
        config: ProtobufRpcGeneratorConfig,
        chunkName: String,
        entityIds: List<MessageEntityId>,
    ): FileSpec {
        val type = TypeSpec.objectBuilder(chunkName)
            .addModifiers(KModifier.INTERNAL)
            .addFunction(
                FunSpec.builder("contribute")
                    .addParameter("builder", ProtobufRpcProtocolBuilder::class)
                    .addCode(buildEntityIdContributorCode(entityIds))
                    .build(),
            )
            .build()
        return FileSpec.builder(config.packageName, chunkName)
            .addType(type)
            .build()
    }

    private fun readMetadata(path: Path): RpcProtocolMetadata {
        return path.inputStream().use { input ->
            json.readValue(input)
        }
    }

    private fun readEntityIds(descriptorSetPath: Path): List<MessageEntityId> {
        val registry = ExtensionRegistry.newInstance().apply {
            add(AsteriaRpcOptionsProto.entityId)
        }
        val descriptorSet = descriptorSetPath.inputStream().use {
            DescriptorProtos.FileDescriptorSet.parseFrom(it, registry)
        }
        return descriptorSet.fileList.flatMap(::entityIdsInFile)
    }

    private fun entityIdsInFile(file: DescriptorProtos.FileDescriptorProto): List<MessageEntityId> {
        val javaPackage = file.options.javaPackage.takeIf { it.isNotBlank() } ?: file.`package`
        val outerClassName =
            file.options.javaOuterClassname.takeIf { it.isNotBlank() } ?: defaultOuterClassName(file.name)
        val multipleFiles = file.options.javaMultipleFiles
        return file.messageTypeList.flatMap { message ->
            entityIdsInMessage(
                message = message,
                javaPackage = javaPackage,
                outerClassName = outerClassName,
                multipleFiles = multipleFiles,
                parents = emptyList(),
            )
        }
    }

    private fun entityIdsInMessage(
        message: DescriptorProtos.DescriptorProto,
        javaPackage: String,
        outerClassName: String,
        multipleFiles: Boolean,
        parents: List<String>,
    ): List<MessageEntityId> {
        val currentPath = parents + message.name
        val ownEntityId = if (message.options.hasExtension(AsteriaRpcOptionsProto.entityId)) {
            val fieldName = message.options.getExtension(AsteriaRpcOptionsProto.entityId)
            val messageClass = messageClassName(javaPackage, outerClassName, multipleFiles, currentPath)
            listOf(MessageEntityId(currentPath.joinToString("."), message, messageClass, fieldName))
        } else {
            emptyList()
        }
        return ownEntityId + message.nestedTypeList.flatMap { nested ->
            entityIdsInMessage(
                message = nested,
                javaPackage = javaPackage,
                outerClassName = outerClassName,
                multipleFiles = multipleFiles,
                parents = currentPath,
            )
        }
    }

    private fun validateMetadata(metadata: RpcProtocolMetadata) {
        require(metadata.messages.isNotEmpty()) { "protobuf RPC metadata must contain at least one message" }
        val messageIds = metadata.messages.map { it.id }
        messageIds.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.let { entry ->
            error("duplicate protobuf RPC message id ${entry.key}")
        }
        val messageTypes = metadata.messages.map { it.type }
        messageTypes.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.let { entry ->
            error("duplicate protobuf RPC message type ${entry.key}")
        }
    }

    private fun buildContributorCode(
        metadata: RpcProtocolMetadata,
        entityIds: List<MessageEntityId>,
    ): CodeBlock {
        return buildMessageContributorCode(metadata.messages.sortedBy { it.id })
            .toBuilder()
            .add(buildEntityIdContributorCode(entityIds))
            .build()
    }

    private fun buildMessageContributorCode(messages: List<RpcMessageSpec>): CodeBlock {
        val builder = CodeBlock.builder()
        messages.forEach { message ->
            val messageClass = ClassName.bestGuess(message.type)
            builder.addStatement(
                "builder.message(id = %L, messageClass = %T::class, parser = %T.parser())",
                message.id,
                messageClass,
                messageClass,
            )
        }
        return builder.build()
    }

    private fun buildEntityIdContributorCode(entityIds: List<MessageEntityId>): CodeBlock {
        val builder = CodeBlock.builder()
        entityIds.forEach { entityId ->
            builder.add("builder.entityId<%T> { message ->\n", entityId.messageClass)
            builder.indent()
            builder.add("message.%L.toString()\n", entityId.fieldName.protoFieldNameToKotlinProperty())
            builder.unindent()
            builder.add("}\n")
        }
        return builder.build()
    }

    private fun buildChunkAggregatorCode(chunkNames: List<String>): CodeBlock {
        val builder = CodeBlock.builder()
        chunkNames.forEach { chunkName ->
            builder.addStatement("%L.contribute(builder)", chunkName)
        }
        return builder.build()
    }

    private fun validateField(entityId: MessageEntityId) {
        require(entityId.fieldName.isNotBlank()) {
            "entity_id for ${entityId.protoName} must not be blank"
        }
        require(entityId.message.fieldList.any { it.name == entityId.fieldName }) {
            "field ${entityId.fieldName} not found in ${entityId.protoName}"
        }
    }

    private fun messageClassName(
        javaPackage: String,
        outerClassName: String,
        multipleFiles: Boolean,
        messagePath: List<String>,
    ): ClassName {
        return if (multipleFiles) {
            ClassName(javaPackage, messagePath.first(), *messagePath.drop(1).toTypedArray())
        } else {
            ClassName(javaPackage, outerClassName, *messagePath.toTypedArray())
        }
    }

    private fun defaultOuterClassName(fileName: String): String {
        val baseName = fileName.substringAfterLast('/').substringBeforeLast('.')
        return baseName.split('_', '-')
            .filter { it.isNotBlank() }
            .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    }

    private fun String.protoFieldNameToKotlinProperty(): String {
        val parts = split('_').filter { it.isNotBlank() }
        return parts.mapIndexed { index, part ->
            if (index == 0) {
                part
            } else {
                part.replaceFirstChar(Char::uppercaseChar)
            }
        }.joinToString("")
    }

    private fun writeServiceProvider(config: ProtobufRpcGeneratorConfig) {
        val serviceDir = config.resourcesOutput
            .resolve("META-INF")
            .resolve("services")
            .also(Path::createDirectories)
        serviceDir
            .resolve(ProtobufRpcProtocolContributor::class.qualifiedName!!)
            .writeText("${config.packageName}.${config.className}\n")
    }

    private val json = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
        .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true)
        .build()

    private const val CONTRIBUTION_CHUNK_SIZE = 200
}

data class GeneratedRpcProtocolFile(
    val fileName: String,
    val file: FileSpec,
)

data class ProtobufRpcGeneratorConfig(
    val metadata: Path,
    val descriptorSet: Path? = null,
    val kotlinOutput: Path,
    val resourcesOutput: Path,
    val packageName: String,
    val className: String,
) {
    companion object {
        fun parse(args: Array<String>): ProtobufRpcGeneratorConfig {
            val values = args.toList().chunked(2).associate { pair ->
                require(pair.size == 2 && pair[0].startsWith("--")) {
                    "arguments must be passed as --key value"
                }
                pair[0] to pair[1]
            }
            return ProtobufRpcGeneratorConfig(
                metadata = Path(requireNotNull(values["--metadata"]) { "--metadata is required" }),
                descriptorSet = values["--descriptor-set"]?.let(::Path),
                kotlinOutput = Path(requireNotNull(values["--kotlin-output"]) { "--kotlin-output is required" })
                    .also { Files.createDirectories(it) },
                resourcesOutput = Path(requireNotNull(values["--resources-output"]) { "--resources-output is required" })
                    .also { Files.createDirectories(it) },
                packageName = values["--package"] ?: "io.github.realmlabs.asteria.generated.rpc",
                className = values["--class-name"] ?: "GeneratedRpcProtocol",
            )
        }
    }
}

data class RpcProtocolMetadata(
    val messages: List<RpcMessageSpec> = emptyList(),
)

data class RpcMessageSpec(
    val id: Int,
    val type: String,
)

private data class MessageEntityId(
    val protoName: String,
    val message: DescriptorProtos.DescriptorProto,
    val messageClass: ClassName,
    val fieldName: String,
)
