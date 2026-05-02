package io.github.mikai233.asteria.rpc.protobuf.generator

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec
import io.github.mikai233.asteria.core.EntityKind
import io.github.mikai233.asteria.core.RoleKey
import io.github.mikai233.asteria.core.SingletonName
import io.github.mikai233.asteria.rpc.RpcProtocolProvider
import io.github.mikai233.asteria.rpc.RpcTarget
import io.github.mikai233.asteria.rpc.protobuf.GeneratedProtobufRpcProtocol
import io.github.mikai233.asteria.rpc.protobuf.ProtobufRpcProtocolBuilder
import io.github.mikai233.asteria.rpc.protobuf.ProtobufRpcProtocolContributor
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
        val file = buildFile(config, metadata)
        file.writeTo(config.kotlinOutput)
        writeServiceProvider(config)
    }

    fun buildFile(
        config: ProtobufRpcGeneratorConfig,
        metadata: RpcProtocolMetadata,
    ): FileSpec {
        validateMetadata(metadata)
        val contributeFunction = FunSpec.builder("contribute")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("builder", ProtobufRpcProtocolBuilder::class)
            .addCode(buildContributorCode(metadata))
            .build()
        val type = TypeSpec.classBuilder(config.className)
            .superclass(GeneratedProtobufRpcProtocol::class)
            .addFunction(contributeFunction)
            .build()
        return FileSpec.builder(config.packageName, config.className)
            .addType(type)
            .build()
    }

    private fun readMetadata(path: Path): RpcProtocolMetadata {
        return path.inputStream().use { input ->
            json.readValue(input)
        }
    }

    private fun validateMetadata(metadata: RpcProtocolMetadata) {
        val messageIds = metadata.messages.map { it.id } +
            metadata.methods.flatMap { method ->
                buildList {
                    add(method.id)
                    method.responseId?.let(::add)
                }
            }
        messageIds.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.let { entry ->
            error("duplicate protobuf RPC message id ${entry.key}")
        }
        metadata.methods.groupBy { it.name }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { name ->
            error("duplicate protobuf RPC method name $name")
        }
        val messageTypes = metadata.messages.map { it.type } +
            metadata.methods.flatMap { method ->
                buildList {
                    add(method.requestType)
                    method.responseType?.let(::add)
                }
            }
        messageTypes.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.let { entry ->
            error("duplicate protobuf RPC message type ${entry.key}")
        }
        metadata.entityIds.groupBy { it.type }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { type ->
            error("duplicate protobuf RPC entity id type $type")
        }
        metadata.methods.forEach { method ->
            when (method.mode) {
                RpcMethodMode.ASK -> {
                    require(method.responseId != null) { "ASK RPC method ${method.name} must declare responseId" }
                    require(!method.responseType.isNullOrBlank()) {
                        "ASK RPC method ${method.name} must declare responseType"
                    }
                }
                RpcMethodMode.TELL -> {
                    require(method.responseId == null) { "TELL RPC method ${method.name} must not declare responseId" }
                    require(method.responseType.isNullOrBlank()) {
                        "TELL RPC method ${method.name} must not declare responseType"
                    }
                }
            }
        }
    }

    private fun buildContributorCode(metadata: RpcProtocolMetadata): CodeBlock {
        val builder = CodeBlock.builder()
        metadata.messages.sortedBy { it.id }.forEach { message ->
            val messageClass = ClassName.bestGuess(message.type)
            builder.addStatement(
                "builder.message(id = %L, messageClass = %T::class, parser = %T.parser())",
                message.id,
                messageClass,
                messageClass,
            )
        }
        metadata.entityIds.sortedBy { it.type }.forEach { entityId ->
            addEntityId(builder, ClassName.bestGuess(entityId.type), entityId.property)
        }
        metadata.methods.sortedBy { it.id }.forEach { method ->
            when (method.mode) {
                RpcMethodMode.ASK -> addAskMethod(builder, method)
                RpcMethodMode.TELL -> addTellMethod(builder, method)
            }
        }
        return builder.build()
    }

    private fun addAskMethod(builder: CodeBlock.Builder, method: RpcMethodSpec) {
        val requestClass = ClassName.bestGuess(method.requestType)
        val responseId = requireNotNull(method.responseId)
        val responseClass = ClassName.bestGuess(requireNotNull(method.responseType))
        builder.add("builder.call(\n")
        builder.indent()
        builder.add("id = %L,\n", method.id)
        builder.add("name = %S,\n", method.name)
        builder.add("requestClass = %T::class,\n", requestClass)
        builder.add("requestParser = %T.parser(),\n", requestClass)
        builder.add("responseId = %L,\n", responseId)
        builder.add("responseClass = %T::class,\n", responseClass)
        builder.add("responseParser = %T.parser(),\n", responseClass)
        builder.add("target = %L", rpcTargetCode(method.target))
        addOptionalEntityIdResolver(builder, method.entityIdProperty)
        builder.add(",\n")
        builder.unindent()
        builder.add(")\n")
    }

    private fun addTellMethod(builder: CodeBlock.Builder, method: RpcMethodSpec) {
        val requestClass = ClassName.bestGuess(method.requestType)
        builder.add("builder.tell(\n")
        builder.indent()
        builder.add("id = %L,\n", method.id)
        builder.add("name = %S,\n", method.name)
        builder.add("requestClass = %T::class,\n", requestClass)
        builder.add("requestParser = %T.parser(),\n", requestClass)
        builder.add("target = %L", rpcTargetCode(method.target))
        addOptionalEntityIdResolver(builder, method.entityIdProperty)
        builder.add(",\n")
        builder.unindent()
        builder.add(")\n")
    }

    private fun addOptionalEntityIdResolver(builder: CodeBlock.Builder, entityIdProperty: String?) {
        if (!entityIdProperty.isNullOrBlank()) {
            builder.add(",\n")
            builder.add("entityIdResolver = { message -> message.%L.toString() }", entityIdProperty)
        }
    }

    private fun addEntityId(builder: CodeBlock.Builder, messageClass: ClassName, property: String) {
        builder.add("builder.entityId(%T::class.java) { message ->\n", messageClass)
        builder.indent()
        builder.add("message.%L.toString()\n", property)
        builder.unindent()
        builder.add("}\n")
    }

    private fun rpcTargetCode(target: RpcTargetSpec): CodeBlock {
        return when (target.type) {
            RpcTargetType.ENTITY -> CodeBlock.of(
                "%T.Entity(%T(%S))",
                RpcTarget::class,
                EntityKind::class,
                requireNotNull(target.name) { "entity RPC target requires name" },
            )
            RpcTargetType.SINGLETON -> CodeBlock.of(
                "%T.Singleton(%T(%S))",
                RpcTarget::class,
                SingletonName::class,
                requireNotNull(target.name) { "singleton RPC target requires name" },
            )
            RpcTargetType.SERVICE -> CodeBlock.of(
                "%T.Service(%T(%S), %S)",
                RpcTarget::class,
                RoleKey::class,
                requireNotNull(target.role) { "service RPC target requires role" },
                requireNotNull(target.path) { "service RPC target requires path" },
            )
        }
    }

    private fun writeServiceProvider(config: ProtobufRpcGeneratorConfig) {
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

    private val json = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true)
        .build()
}

data class ProtobufRpcGeneratorConfig(
    val metadata: Path,
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
                kotlinOutput = Path(requireNotNull(values["--kotlin-output"]) { "--kotlin-output is required" })
                    .also { Files.createDirectories(it) },
                resourcesOutput = Path(requireNotNull(values["--resources-output"]) { "--resources-output is required" })
                    .also { Files.createDirectories(it) },
                packageName = values["--package"] ?: "io.github.mikai233.asteria.generated.rpc",
                className = values["--class-name"] ?: "GeneratedRpcProtocol",
            )
        }
    }
}

data class RpcProtocolMetadata(
    val messages: List<RpcMessageSpec> = emptyList(),
    val entityIds: List<RpcEntityIdSpec> = emptyList(),
    val methods: List<RpcMethodSpec> = emptyList(),
)

data class RpcMessageSpec(
    val id: Int,
    val type: String,
)

data class RpcEntityIdSpec(
    val type: String,
    val property: String,
)

data class RpcMethodSpec(
    val id: Int,
    val name: String,
    val mode: RpcMethodMode,
    val requestType: String,
    val target: RpcTargetSpec,
    val responseId: Int? = null,
    val responseType: String? = null,
    val entityIdProperty: String? = null,
)

enum class RpcMethodMode {
    ASK,
    TELL,
}

data class RpcTargetSpec(
    val type: RpcTargetType,
    val name: String? = null,
    val role: String? = null,
    val path: String? = null,
)

enum class RpcTargetType {
    ENTITY,
    SINGLETON,
    SERVICE,
}
