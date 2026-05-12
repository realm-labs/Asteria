package io.github.realmlabs.asteria.protocol.protobuf.gradle

import io.github.realmlabs.asteria.rpc.protobuf.generator.ProtobufRpcGeneratorConfig
import io.github.realmlabs.asteria.rpc.protobuf.generator.ProtobufRpcProtocolGenerator
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

/**
 * Cacheable Gradle task that generates the RPC protobuf protocol registry sources.
 *
 * The task is skipped when [generationEnabled] is false. [metadataFile] is required when enabled, while
 * [descriptorSetFile] is optional and is passed through to the generator when available.
 */
@CacheableTask
abstract class AsteriaGenerateRpcProtocolTask : DefaultTask() {
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

    init {
        onlyIf { generationEnabled.get() }
    }

    @TaskAction
    fun generate() {
        ProtobufRpcProtocolGenerator.generate(
            ProtobufRpcGeneratorConfig(
                metadata = metadataFile.get().asFile.toPath(),
                descriptorSet = descriptorSetFile.orNull?.asFile?.toPath(),
                kotlinOutput = kotlinOutputDirectory.get().asFile.toPath(),
                resourcesOutput = resourcesOutputDirectory.get().asFile.toPath(),
                packageName = packageName.get(),
                className = className.get(),
            ),
        )
    }
}
