package io.github.mikai233.asteria.protocol.protobuf.gradle

import io.github.mikai233.asteria.rpc.protobuf.generator.ProtobufRpcGeneratorConfig
import io.github.mikai233.asteria.rpc.protobuf.generator.ProtobufRpcProtocolGenerator
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class AsteriaGenerateRpcProtocolTask : DefaultTask() {
    @get:Input
    abstract val generationEnabled: Property<Boolean>

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val metadataFile: RegularFileProperty

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
                kotlinOutput = kotlinOutputDirectory.get().asFile.toPath(),
                resourcesOutput = resourcesOutputDirectory.get().asFile.toPath(),
                packageName = packageName.get(),
                className = className.get(),
            ),
        )
    }
}
