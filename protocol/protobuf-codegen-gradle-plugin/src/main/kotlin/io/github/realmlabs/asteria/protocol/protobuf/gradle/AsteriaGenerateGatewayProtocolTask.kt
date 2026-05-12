package io.github.realmlabs.asteria.protocol.protobuf.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import io.github.realmlabs.asteria.protocol.protobuf.generator.ProtobufGatewayGeneratorConfig
import io.github.realmlabs.asteria.protocol.protobuf.generator.ProtobufGatewayProtocolGenerator
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.File

/**
 * Cacheable Gradle task that generates gateway protobuf protocol registry sources.
 *
 * When [clientMetadataEnabled] is true, a compact JSON file for client tooling is also emitted from the same metadata
 * input. The extra file intentionally contains only client-relevant message fields.
 */
@CacheableTask
abstract class AsteriaGenerateGatewayProtocolTask : DefaultTask() {
    @get:Input
    abstract val generationEnabled: Property<Boolean>

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val metadataFile: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val descriptorSetFile: RegularFileProperty

    @get:OutputDirectory
    abstract val kotlinOutputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val resourcesOutputDirectory: DirectoryProperty

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val className: Property<String>

    @get:Input
    abstract val clientMetadataEnabled: Property<Boolean>

    @get:Optional
    @get:OutputFile
    abstract val clientMetadataFile: RegularFileProperty

    init {
        onlyIf { generationEnabled.get() }
    }

    @TaskAction
    fun generate() {
        ProtobufGatewayProtocolGenerator.generate(
            ProtobufGatewayGeneratorConfig(
                metadata = metadataFile.get().asFile.toPath(),
                kotlinOutput = kotlinOutputDirectory.get().asFile.toPath(),
                resourcesOutput = resourcesOutputDirectory.get().asFile.toPath(),
                packageName = packageName.get(),
                className = className.get(),
                descriptorSet = descriptorSetFile.orNull?.asFile?.toPath(),
            ),
        )
        if (clientMetadataEnabled.get()) {
            writeClientMetadata(metadataFile.get().asFile, clientMetadataFile.get().asFile)
        }
    }

    private fun writeClientMetadata(metadataFile: File, outputFile: File) {
        val messages = when (val root = JsonSlurper().parse(metadataFile)) {
            is Map<*, *> -> root["messages"] as? List<*>
                ?: error("gateway protocol metadata must contain a messages array")

            else -> error("gateway protocol metadata must be a JSON object")
        }
        val clientMessages = messages.mapIndexed { index, value ->
            val message = value as? Map<*, *>
                ?: error("gateway protocol message at index $index must be an object")
            linkedMapOf(
                "id" to message.required("id", index),
                "type" to message.required("type", index),
                "direction" to message.required("direction", index),
            ).apply {
                message["name"]?.let { put("name", it) }
                message["requestName"]?.let { put("requestName", it) }
                message["responseTo"]?.let { put("responseTo", it) }
            }
        }
        outputFile.parentFile.mkdirs()
        outputFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("messages" to clientMessages))))
    }

    private fun Map<*, *>.required(name: String, index: Int): Any {
        return requireNotNull(this[name]) {
            "gateway protocol message at index $index must contain $name"
        }
    }
}
