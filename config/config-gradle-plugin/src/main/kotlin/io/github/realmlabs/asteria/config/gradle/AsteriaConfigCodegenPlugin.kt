package io.github.realmlabs.asteria.config.gradle

import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

class AsteriaConfigCodegenPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "asteriaConfigCodegen",
            AsteriaConfigCodegenExtension::class.java,
        )
        extension.asteriaVersion.convention(pluginVersion())

        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            project.pluginManager.apply(KSP_PLUGIN_ID)
            configureKsp(project, extension)
            configureLubanMarkers(project, extension)
            project.afterEvaluate {
                if (extension.addDependencies.get()) {
                    addAsteriaDependencies(project, extension.asteriaVersion.get())
                }
            }
        }
    }

    private fun configureLubanMarkers(project: Project, extension: AsteriaConfigCodegenExtension) {
        val markerTask = project.tasks.register(
            "generateAsteriaLubanConfigMarkers",
            AsteriaLubanConfigMarkerTask::class.java,
        ) { task ->
            task.generationEnabled.set(extension.luban.enabled)
            task.metadataFile.set(extension.luban.metadataFile)
            task.outputDirectory.set(project.layout.buildDirectory.dir("generated/asteria/lubanConfigMarkers"))
            task.packageName.set(
                extension.luban.packageName.flatMap { markerPackage ->
                    if (markerPackage.isBlank()) {
                        extension.packageName
                    } else {
                        project.providers.provider { markerPackage }
                    }
                },
            )
            task.fileName.set(extension.luban.fileName)
            task.tablesObjectName.set(extension.tablesObjectName)
            task.accessorClassName.set(extension.accessorClassName)
        }
        project.extensions.configure(KotlinJvmProjectExtension::class.java) { kotlin ->
            kotlin.sourceSets.getByName("main").kotlin.srcDir(markerTask.flatMap { it.outputDirectory })
        }
        project.tasks.matching { it.name == "kspKotlin" }.configureEach {
            it.dependsOn(markerTask)
        }
    }

    private fun configureKsp(project: Project, extension: AsteriaConfigCodegenExtension) {
        project.extensions.configure(KspExtension::class.java) { ksp ->
            ksp.arg("asteria.config.package", extension.packageName)
            ksp.arg("asteria.config.tables", extension.tablesObjectName)
            ksp.arg("asteria.config.accessor", extension.accessorClassName)
        }
    }

    private fun addAsteriaDependencies(project: Project, version: String) {
        project.dependencies.addIfConfigurationExists(
            project,
            "implementation",
            project.localProjectOrCoordinate(":config:config-core", "config-core", version),
        )
        project.dependencies.addIfConfigurationExists(
            project,
            "implementation",
            project.localProjectOrCoordinate(":config:config-annotations", "config-annotations", version),
        )
        project.dependencies.add(
            "ksp",
            project.localProjectOrCoordinate(":config:config-ksp", "config-ksp", version),
        )
    }

    private fun org.gradle.api.artifacts.dsl.DependencyHandler.addIfConfigurationExists(
        project: Project,
        configurationName: String,
        dependencyNotation: Any,
    ) {
        if (project.configurations.findByName(configurationName) == null) {
            project.logger.warn(
                "Asteria config codegen skipped dependency $dependencyNotation because configuration " +
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
        return AsteriaConfigCodegenPlugin::class.java.`package`.implementationVersion
            ?: DEFAULT_ASTERIA_VERSION
    }

    companion object {
        const val DEFAULT_ASTERIA_VERSION = "1.0-SNAPSHOT"
        private const val ASTERIA_GROUP = "io.github.realm-labs"
        private const val KSP_PLUGIN_ID = "com.google.devtools.ksp"
    }
}
