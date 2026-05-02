package io.github.mikai233.asteria.rpc.protobuf.generator

import com.google.protobuf.DescriptorProtos
import com.google.protobuf.ExtensionRegistry
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec
import io.github.mikai233.asteria.rpc.RpcProtocolProvider
import io.github.mikai233.asteria.rpc.protobuf.AsteriaRpcOptionsProto
import io.github.mikai233.asteria.rpc.protobuf.GeneratedProtobufRpcProtocol
import io.github.mikai233.asteria.rpc.protobuf.ProtobufRpcProtocolContributor
import io.github.mikai233.asteria.rpc.protobuf.ProtobufRpcProtocolBuilder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.writeText

object ProtobufRpcEntityIdGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = GeneratorConfig.parse(args)
        generate(config)
    }

    fun generate(config: GeneratorConfig) {
        val entityIds = readEntityIds(config.descriptorSet)
        val file = buildEntityIdFile(config, entityIds)
        file.writeTo(config.kotlinOutput)
        writeServiceProvider(config)
    }

    private fun readEntityIds(descriptorSetPath: Path): List<MessageEntityId> {
        val registry = ExtensionRegistry.newInstance().apply {
            add(AsteriaRpcOptionsProto.rpcEntityIdField)
        }
        val descriptorSet = descriptorSetPath.inputStream().use {
            DescriptorProtos.FileDescriptorSet.parseFrom(it, registry)
        }
        return descriptorSet.fileList.flatMap(::entityIdsInFile)
    }

    private fun entityIdsInFile(file: DescriptorProtos.FileDescriptorProto): List<MessageEntityId> {
        val javaPackage = file.options.javaPackage.takeIf { it.isNotBlank() } ?: file.`package`
        val outerClassName = file.options.javaOuterClassname.takeIf { it.isNotBlank() } ?: defaultOuterClassName(file.name)
        val multipleFiles = file.options.javaMultipleFiles
        return file.messageTypeList.flatMap { message ->
            entityIdsInMessage(
                file = file,
                message = message,
                javaPackage = javaPackage,
                outerClassName = outerClassName,
                multipleFiles = multipleFiles,
                parents = emptyList(),
            )
        }
    }

    private fun entityIdsInMessage(
        file: DescriptorProtos.FileDescriptorProto,
        message: DescriptorProtos.DescriptorProto,
        javaPackage: String,
        outerClassName: String,
        multipleFiles: Boolean,
        parents: List<String>,
    ): List<MessageEntityId> {
        val currentPath = parents + message.name
        val ownEntityId = if (message.options.hasExtension(AsteriaRpcOptionsProto.rpcEntityIdField)) {
            val fieldName = message.options.getExtension(AsteriaRpcOptionsProto.rpcEntityIdField)
            val messageClass = messageClassName(javaPackage, outerClassName, multipleFiles, currentPath)
            listOf(MessageEntityId(file.name, currentPath.joinToString("."), message, messageClass, fieldName))
        } else {
            emptyList()
        }
        return ownEntityId + message.nestedTypeList.flatMap { nested ->
            entityIdsInMessage(file, nested, javaPackage, outerClassName, multipleFiles, currentPath)
        }
    }

    private fun buildEntityIdFile(
        config: GeneratorConfig,
        entityIds: List<MessageEntityId>,
    ): FileSpec {
        val contributeFunction = FunSpec.builder("contribute")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("builder", ProtobufRpcProtocolBuilder::class)
            .addCode(buildContributorCode(entityIds))
            .build()
        val entityIdType = TypeSpec.classBuilder(config.className)
            .superclass(GeneratedProtobufRpcProtocol::class)
            .addFunction(contributeFunction)
            .build()
        return FileSpec.builder(config.packageName, config.className)
            .addType(entityIdType)
            .build()
    }

    private fun buildContributorCode(entityIds: List<MessageEntityId>): CodeBlock {
        val builder = CodeBlock.builder()
        entityIds.forEach { entityId ->
            validateField(entityId)
            builder.add("builder.entityId<%T> { message ->\n", entityId.messageClass)
            builder.indent()
            builder.add("message.%L.toString()\n", entityId.fieldName.protoFieldNameToKotlinProperty())
            builder.unindent()
            builder.add("}\n")
        }
        return builder.build()
    }

    private fun validateField(entityId: MessageEntityId) {
        require(entityId.fieldName.isNotBlank()) {
            "rpc_entity_id_field for ${entityId.protoName} must not be blank"
        }
        require(entityId.message.fieldList.any { it.name == entityId.fieldName }) {
            "field ${entityId.fieldName} not found in ${entityId.protoName}"
        }
    }

    private fun writeServiceProvider(config: GeneratorConfig) {
        val serviceDir = config.resourcesOutput
            .resolve("META-INF")
            .resolve("services")
            .also(Path::createDirectories)
        serviceDir
            .resolve(RpcProtocolProvider::class.qualifiedName!!)
            .writeText("${config.packageName}.${config.className}\n")
        serviceDir
            .resolve(ProtobufRpcProtocolContributor::class.qualifiedName!!)
            .writeText("${config.packageName}.${config.className}\n")
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

}

data class GeneratorConfig(
    val descriptorSet: Path,
    val kotlinOutput: Path,
    val resourcesOutput: Path,
    val packageName: String,
    val className: String,
) {
    companion object {
        fun parse(args: Array<String>): GeneratorConfig {
            val values = args.toList().chunked(2).associate { pair ->
                require(pair.size == 2 && pair[0].startsWith("--")) {
                    "arguments must be passed as --key value"
                }
                pair[0] to pair[1]
            }
            return GeneratorConfig(
                descriptorSet = Path(requireNotNull(values["--descriptor-set"]) { "--descriptor-set is required" }),
                kotlinOutput = Path(requireNotNull(values["--kotlin-output"]) { "--kotlin-output is required" })
                    .also { Files.createDirectories(it) },
                resourcesOutput = Path(requireNotNull(values["--resources-output"]) { "--resources-output is required" })
                    .also { Files.createDirectories(it) },
                packageName = values["--package"] ?: "io.github.mikai233.asteria.generated.rpc",
                className = values["--class-name"] ?: "GeneratedRpcEntityIds",
            )
        }
    }
}

private data class MessageEntityId(
    val fileName: String,
    val protoName: String,
    val message: DescriptorProtos.DescriptorProto,
    val messageClass: ClassName,
    val fieldName: String,
)
