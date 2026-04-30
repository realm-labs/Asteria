package io.github.mikai233.asteria.rpc.protobuf.generator

import com.google.protobuf.DescriptorProtos
import com.google.protobuf.ExtensionRegistry
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.TypeSpec
import io.github.mikai233.asteria.core.EntityKind
import io.github.mikai233.asteria.core.RoleKey
import io.github.mikai233.asteria.core.SingletonName
import io.github.mikai233.asteria.rpc.RpcRouteRegistryProvider
import io.github.mikai233.asteria.rpc.RpcTarget
import io.github.mikai233.asteria.rpc.protobuf.AsteriaRpcOptionsProto
import io.github.mikai233.asteria.rpc.protobuf.GeneratedProtobufRpcRoutes
import io.github.mikai233.asteria.rpc.protobuf.ProtobufRpcRouteRegistry
import io.github.mikai233.asteria.rpc.protobuf.protobufRpcRouteRegistry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.writeText

object ProtobufRpcRouteGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = GeneratorConfig.parse(args)
        generate(config)
    }

    fun generate(config: GeneratorConfig) {
        val routes = readRoutes(config.descriptorSet)
        val file = buildRouteFile(config, routes)
        file.writeTo(config.kotlinOutput)
        writeServiceProvider(config)
    }

    private fun readRoutes(descriptorSetPath: Path): List<MessageRoute> {
        val registry = ExtensionRegistry.newInstance().apply {
            add(AsteriaRpcOptionsProto.rpcRoute)
        }
        val descriptorSet = descriptorSetPath.inputStream().use {
            DescriptorProtos.FileDescriptorSet.parseFrom(it, registry)
        }
        return descriptorSet.fileList.flatMap(::routesInFile)
    }

    private fun routesInFile(file: DescriptorProtos.FileDescriptorProto): List<MessageRoute> {
        val javaPackage = file.options.javaPackage.takeIf { it.isNotBlank() } ?: file.`package`
        val outerClassName = file.options.javaOuterClassname.takeIf { it.isNotBlank() } ?: defaultOuterClassName(file.name)
        val multipleFiles = file.options.javaMultipleFiles
        return file.messageTypeList.flatMap { message ->
            routesInMessage(
                file = file,
                message = message,
                javaPackage = javaPackage,
                outerClassName = outerClassName,
                multipleFiles = multipleFiles,
                parents = emptyList(),
            )
        }
    }

    private fun routesInMessage(
        file: DescriptorProtos.FileDescriptorProto,
        message: DescriptorProtos.DescriptorProto,
        javaPackage: String,
        outerClassName: String,
        multipleFiles: Boolean,
        parents: List<String>,
    ): List<MessageRoute> {
        val currentPath = parents + message.name
        val ownRoute = if (message.options.hasExtension(AsteriaRpcOptionsProto.rpcRoute)) {
            val rpcRoute = message.options.getExtension(AsteriaRpcOptionsProto.rpcRoute)
            val messageClass = messageClassName(javaPackage, outerClassName, multipleFiles, currentPath)
            listOf(MessageRoute(file.name, currentPath.joinToString("."), message, messageClass, rpcRoute))
        } else {
            emptyList()
        }
        return ownRoute + message.nestedTypeList.flatMap { nested ->
            routesInMessage(file, nested, javaPackage, outerClassName, multipleFiles, currentPath)
        }
    }

    private fun buildRouteFile(
        config: GeneratorConfig,
        routes: List<MessageRoute>,
    ): FileSpec {
        val registryFunction = FunSpec.builder("registry")
            .addModifiers(KModifier.OVERRIDE, KModifier.PROTECTED)
            .returns(ProtobufRpcRouteRegistry::class)
            .addCode(buildRegistryCode(routes))
            .build()
        val routeType = TypeSpec.objectBuilder(config.className)
            .superclass(GeneratedProtobufRpcRoutes::class)
            .addFunction(registryFunction)
            .build()
        return FileSpec.builder(config.packageName, config.className)
            .addType(routeType)
            .build()
    }

    private fun buildRegistryCode(routes: List<MessageRoute>): CodeBlock {
        val builder = CodeBlock.builder()
        builder.add("return %M {\n", MemberName(PROTOBUF_RPC_PACKAGE, "protobufRpcRouteRegistry"))
        builder.indent()
        routes.forEach { route ->
            builder.add("route<%T> { message ->\n", route.messageClass)
            builder.indent()
            builder.add(routeTargetCode(route))
            builder.add("\n")
            builder.unindent()
            builder.add("}\n")
        }
        builder.unindent()
        builder.add("}\n")
        return builder.build()
    }

    private fun routeTargetCode(route: MessageRoute): CodeBlock {
        val rpcRoute = route.route
        return when (rpcRoute.targetCase) {
            AsteriaRpcOptionsProto.RpcRoute.TargetCase.ENTITY -> {
                val entity = rpcRoute.entity
                validateField(route, entity.idField)
                CodeBlock.of(
                    "%T.Entity(%T(%S), message.%L.toString())",
                    RpcTarget::class,
                    EntityKind::class,
                    entity.kind,
                    entity.idField.protoFieldNameToKotlinProperty(),
                )
            }

            AsteriaRpcOptionsProto.RpcRoute.TargetCase.SINGLETON -> {
                CodeBlock.of(
                    "%T.Singleton(%T(%S))",
                    RpcTarget::class,
                    SingletonName::class,
                    rpcRoute.singleton.name,
                )
            }

            AsteriaRpcOptionsProto.RpcRoute.TargetCase.SERVICE -> {
                CodeBlock.of(
                    "%T.Service(%T(%S), %S)",
                    RpcTarget::class,
                    RoleKey::class,
                    rpcRoute.service.role,
                    rpcRoute.service.path,
                )
            }

            AsteriaRpcOptionsProto.RpcRoute.TargetCase.LOCAL -> CodeBlock.of("%T.Local", RpcTarget::class)
            AsteriaRpcOptionsProto.RpcRoute.TargetCase.TARGET_NOT_SET,
            null,
            -> error("rpc route target not set for ${route.protoName}")
        }
    }

    private fun validateField(route: MessageRoute, fieldName: String) {
        require(fieldName.isNotBlank()) {
            "entity id_field for ${route.protoName} must not be blank"
        }
        require(route.message.fieldList.any { it.name == fieldName }) {
            "field $fieldName not found in ${route.protoName}"
        }
    }

    private fun writeServiceProvider(config: GeneratorConfig) {
        val serviceDir = config.resourcesOutput
            .resolve("META-INF")
            .resolve("services")
            .also(Path::createDirectories)
        serviceDir
            .resolve(RpcRouteRegistryProvider::class.qualifiedName!!)
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

    private const val PROTOBUF_RPC_PACKAGE = "io.github.mikai233.asteria.rpc.protobuf"
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
                className = values["--class-name"] ?: "GeneratedRpcRoutes",
            )
        }
    }
}

private data class MessageRoute(
    val fileName: String,
    val protoName: String,
    val message: DescriptorProtos.DescriptorProto,
    val messageClass: ClassName,
    val route: AsteriaRpcOptionsProto.RpcRoute,
)
