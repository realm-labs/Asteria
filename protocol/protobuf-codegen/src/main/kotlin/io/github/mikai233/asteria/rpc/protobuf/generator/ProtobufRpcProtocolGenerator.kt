package io.github.mikai233.asteria.rpc.protobuf.generator

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.squareup.kotlinpoet.*
import io.github.mikai233.asteria.rpc.RpcProtocolProvider
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
        return builder.build()
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
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
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
)

data class RpcMessageSpec(
    val id: Int,
    val type: String,
)
