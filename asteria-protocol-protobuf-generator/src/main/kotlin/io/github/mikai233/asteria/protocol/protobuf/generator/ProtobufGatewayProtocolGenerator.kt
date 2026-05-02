package io.github.mikai233.asteria.protocol.protobuf.generator

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
import io.github.mikai233.asteria.message.RouteTarget
import io.github.mikai233.asteria.protocol.protobuf.GeneratedProtobufGatewayProtocol
import io.github.mikai233.asteria.protocol.protobuf.ProtobufGatewayProtocolContributor
import io.github.mikai233.asteria.protocol.protobuf.ProtobufGatewayProtocolProvider
import io.github.mikai233.asteria.protocol.protobuf.ProtobufGatewayProtocolBuilder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.writeText

object ProtobufGatewayProtocolGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        generate(ProtobufGatewayGeneratorConfig.parse(args))
    }

    fun generate(config: ProtobufGatewayGeneratorConfig) {
        val metadata = readMetadata(config.metadata)
        val file = buildFile(config, metadata.messages)
        file.writeTo(config.kotlinOutput)
        writeServiceProvider(config)
    }

    fun buildFile(
        config: ProtobufGatewayGeneratorConfig,
        messages: List<GatewayMessageSpec>,
    ): FileSpec {
        validateMessages(messages)
        val contributeFunction = FunSpec.builder("contribute")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("builder", ProtobufGatewayProtocolBuilder::class)
            .addCode(buildContributorCode(messages.sortedBy { it.id }))
            .build()
        val type = TypeSpec.classBuilder(config.className)
            .superclass(GeneratedProtobufGatewayProtocol::class)
            .addFunction(contributeFunction)
            .build()
        return FileSpec.builder(config.packageName, config.className)
            .addType(type)
            .build()
    }

    private fun readMetadata(path: Path): GatewayProtocolMetadata {
        return path.inputStream().use { input ->
            json.readValue(input)
        }
    }

    private fun validateMessages(messages: List<GatewayMessageSpec>) {
        require(messages.isNotEmpty()) { "gateway protobuf metadata must contain at least one message" }
        messages.groupBy { it.id }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { id ->
            error("duplicate gateway protobuf id $id")
        }
        messages.groupBy { it.type }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { type ->
            error("duplicate gateway protobuf message type $type")
        }
        messages.forEach { message ->
            if (message.direction != GatewayMessageDirection.SERVER) {
                require(message.target != null) {
                    "gateway protobuf message ${message.type} must declare target for ${message.direction}"
                }
            }
        }
    }

    private fun buildContributorCode(messages: List<GatewayMessageSpec>): CodeBlock {
        val builder = CodeBlock.builder()
        messages.forEach { message ->
            val messageClass = ClassName.bestGuess(message.type)
            val functionName = when (message.direction) {
                GatewayMessageDirection.CLIENT -> "clientMessage"
                GatewayMessageDirection.SERVER -> "serverMessage"
                GatewayMessageDirection.BIDIRECTIONAL -> "bidirectionalMessage"
            }
            builder.add("builder.%L(\n", functionName)
            builder.indent()
            builder.add("id = %L,\n", message.id)
            builder.add("messageClass = %T::class,\n", messageClass)
            builder.add("parser = %T.parser()", messageClass)
            if (message.direction == GatewayMessageDirection.SERVER) {
                builder.add(",\n")
            } else {
                builder.add(",\n")
                builder.add("target = %L", routeTargetCode(requireNotNull(message.target)))
                if (!message.idProperty.isNullOrBlank()) {
                    builder.add(",\n")
                    builder.add("idResolver = { message -> message.%L }", message.idProperty)
                }
                builder.add(",\n")
            }
            builder.unindent()
            builder.add(")\n")
        }
        return builder.build()
    }

    private fun routeTargetCode(target: GatewayRouteTargetSpec): CodeBlock {
        return when (target.type) {
            GatewayRouteTargetType.ENTITY -> CodeBlock.of(
                "%T.Entity(%T(%S))",
                RouteTarget::class,
                EntityKind::class,
                requireNotNull(target.name) { "entity route target requires name" },
            )
            GatewayRouteTargetType.SINGLETON -> CodeBlock.of(
                "%T.Singleton(%T(%S))",
                RouteTarget::class,
                SingletonName::class,
                requireNotNull(target.name) { "singleton route target requires name" },
            )
            GatewayRouteTargetType.SERVICE -> CodeBlock.of(
                "%T.Service(%T(%S), %S)",
                RouteTarget::class,
                RoleKey::class,
                requireNotNull(target.role) { "service route target requires role" },
                requireNotNull(target.path) { "service route target requires path" },
            )
            GatewayRouteTargetType.GATEWAY_LOCAL -> CodeBlock.of("%T.GatewayLocal", RouteTarget::class)
        }
    }

    private fun writeServiceProvider(config: ProtobufGatewayGeneratorConfig) {
        val serviceDir = config.resourcesOutput
            .resolve("META-INF")
            .resolve("services")
            .also(Path::createDirectories)
        serviceDir
            .resolve(ProtobufGatewayProtocolProvider::class.qualifiedName!!)
            .writeText("${config.packageName}.${config.className}\n")
        serviceDir
            .resolve(ProtobufGatewayProtocolContributor::class.qualifiedName!!)
            .writeText("${config.packageName}.${config.className}\n")
    }

    private val json = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true)
        .build()
}

data class ProtobufGatewayGeneratorConfig(
    val metadata: Path,
    val kotlinOutput: Path,
    val resourcesOutput: Path,
    val packageName: String,
    val className: String,
) {
    companion object {
        fun parse(args: Array<String>): ProtobufGatewayGeneratorConfig {
            val values = args.toList().chunked(2).associate { pair ->
                require(pair.size == 2 && pair[0].startsWith("--")) {
                    "arguments must be passed as --key value"
                }
                pair[0] to pair[1]
            }
            return ProtobufGatewayGeneratorConfig(
                metadata = Path(requireNotNull(values["--metadata"]) { "--metadata is required" }),
                kotlinOutput = Path(requireNotNull(values["--kotlin-output"]) { "--kotlin-output is required" })
                    .also { Files.createDirectories(it) },
                resourcesOutput = Path(requireNotNull(values["--resources-output"]) { "--resources-output is required" })
                    .also { Files.createDirectories(it) },
                packageName = values["--package"] ?: "io.github.mikai233.asteria.generated.protocol",
                className = values["--class-name"] ?: "GeneratedGatewayProtocol",
            )
        }
    }
}

data class GatewayProtocolMetadata(
    val messages: List<GatewayMessageSpec> = emptyList(),
)

data class GatewayMessageSpec(
    val id: Int,
    val type: String,
    val direction: GatewayMessageDirection,
    val target: GatewayRouteTargetSpec? = null,
    val idProperty: String? = null,
)

enum class GatewayMessageDirection {
    CLIENT,
    SERVER,
    BIDIRECTIONAL,
}

data class GatewayRouteTargetSpec(
    val type: GatewayRouteTargetType,
    val name: String? = null,
    val role: String? = null,
    val path: String? = null,
)

enum class GatewayRouteTargetType {
    ENTITY,
    SINGLETON,
    SERVICE,
    GATEWAY_LOCAL,
}
