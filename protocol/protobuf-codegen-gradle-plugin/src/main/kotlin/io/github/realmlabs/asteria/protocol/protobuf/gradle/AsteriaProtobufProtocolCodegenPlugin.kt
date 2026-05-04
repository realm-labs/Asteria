package io.github.realmlabs.asteria.protocol.protobuf.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

class AsteriaProtobufProtocolCodegenPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "asteriaProtobufProtocol",
            AsteriaProtobufProtocolCodegenExtension::class.java,
        )
        extension.asteriaVersion.convention(pluginVersion())

        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            val generatedKotlin = project.layout.buildDirectory.dir("generated/asteria/protobufProtocol/kotlin")
            val generatedResources = project.layout.buildDirectory.dir("generated/asteria/protobufProtocol/resources")

            val gatewayTask = configureGatewayProtocol(project, extension, generatedKotlin, generatedResources)
            val rpcTask = configureRpcProtocol(project, extension, generatedKotlin, generatedResources)
            configureGenerateProtoDependency(project, gatewayTask, rpcTask)

            project.extensions.configure(KotlinJvmProjectExtension::class.java) { kotlin ->
                kotlin.sourceSets.getByName("main").kotlin.srcDir(generatedKotlin)
            }
            project.extensions.configure(org.gradle.api.plugins.JavaPluginExtension::class.java) { java ->
                java.sourceSets.getByName("main").resources.srcDir(generatedResources)
            }
            project.tasks.matching { it.name == "compileKotlin" }.configureEach {
                it.dependsOn(gatewayTask)
                it.dependsOn(rpcTask)
            }
            project.tasks.matching { it.name == "processResources" }.configureEach {
                it.dependsOn(gatewayTask)
                it.dependsOn(rpcTask)
            }
            project.afterEvaluate {
                if (extension.addDependencies.get()) {
                    addAsteriaDependencies(project, extension.asteriaVersion.get())
                }
            }
        }
    }

    private fun configureGenerateProtoDependency(
        project: Project,
        vararg protocolTasks: org.gradle.api.tasks.TaskProvider<out org.gradle.api.Task>,
    ) {
        project.pluginManager.withPlugin("com.google.protobuf") {
            protocolTasks.forEach { protocolTask ->
                protocolTask.configure {
                    it.dependsOn("generateProto")
                }
            }
        }
    }

    private fun configureGatewayProtocol(
        project: Project,
        extension: AsteriaProtobufProtocolCodegenExtension,
        generatedKotlin: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>,
        generatedResources: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>,
    ): org.gradle.api.tasks.TaskProvider<AsteriaGenerateGatewayProtocolTask> {
        return project.tasks.register(
            "generateAsteriaGatewayProtocol",
            AsteriaGenerateGatewayProtocolTask::class.java
        ) {
            it.generationEnabled.set(extension.gateway.enabled)
            it.metadataFile.set(extension.gateway.metadataFile)
            it.descriptorSetFile.set(extension.gateway.descriptorSetFile)
            it.kotlinOutputDirectory.set(generatedKotlin.map { directory -> directory.dir("gateway") })
            it.resourcesOutputDirectory.set(generatedResources)
            it.packageName.set(
                extension.gateway.packageName.orElse(extension.packageName.map { packageName -> "$packageName.gateway" }),
            )
            it.className.set(extension.gateway.className)
            it.clientMetadataEnabled.set(extension.gateway.clientMetadataEnabled)
            it.clientMetadataFile.set(
                extension.gateway.clientMetadataFile.orElse(
                    project.layout.buildDirectory.file("generated/asteria/protobufProtocol/client/gateway-protocol.json"),
                ),
            )
        }
    }

    private fun configureRpcProtocol(
        project: Project,
        extension: AsteriaProtobufProtocolCodegenExtension,
        generatedKotlin: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>,
        generatedResources: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>,
    ): org.gradle.api.tasks.TaskProvider<AsteriaGenerateRpcProtocolTask> {
        return project.tasks.register("generateAsteriaRpcProtocol", AsteriaGenerateRpcProtocolTask::class.java) {
            it.generationEnabled.set(extension.rpc.enabled)
            it.metadataFile.set(extension.rpc.metadataFile)
            it.descriptorSetFile.set(extension.rpc.descriptorSetFile)
            it.kotlinOutputDirectory.set(generatedKotlin.map { directory -> directory.dir("rpc") })
            it.resourcesOutputDirectory.set(generatedResources)
            it.packageName.set(
                extension.rpc.packageName.orElse(extension.packageName.map { packageName -> "$packageName.rpc" }),
            )
            it.className.set(extension.rpc.className)
        }
    }

    private fun addAsteriaDependencies(project: Project, version: String) {
        project.dependencies.addIfConfigurationExists(
            project,
            "implementation",
            project.localProjectOrCoordinate(":protocol:protocol-protobuf", "protocol-protobuf", version),
        )
        project.dependencies.addIfConfigurationExists(
            project,
            "implementation",
            project.localProjectOrCoordinate(":rpc:rpc-protobuf", "rpc-protobuf", version),
        )
    }

    private fun org.gradle.api.artifacts.dsl.DependencyHandler.addIfConfigurationExists(
        project: Project,
        configurationName: String,
        dependencyNotation: Any,
    ) {
        if (project.configurations.findByName(configurationName) == null) {
            project.logger.warn(
                "Asteria protobuf protocol codegen skipped dependency $dependencyNotation because configuration " +
                        "$configurationName does not exist",
            )
            return
        }
        add(configurationName, dependencyNotation)
    }

    private fun Project.localProjectOrCoordinate(projectPath: String, artifactId: String, version: String): Any {
        return rootProject.findProject(projectPath) ?: "$ASTERIA_GROUP:$artifactId:$version"
    }

    private fun pluginVersion(): String {
        return AsteriaProtobufProtocolCodegenPlugin::class.java.`package`.implementationVersion
            ?: DEFAULT_ASTERIA_VERSION
    }

    companion object {
        const val DEFAULT_ASTERIA_VERSION = "1.0-SNAPSHOT"
        private const val ASTERIA_GROUP = "io.github.realm-labs.asteria"
    }
}
