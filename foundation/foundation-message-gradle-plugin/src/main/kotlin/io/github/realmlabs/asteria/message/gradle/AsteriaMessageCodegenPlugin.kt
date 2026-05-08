package io.github.realmlabs.asteria.message.gradle

import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class AsteriaMessageCodegenPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "asteriaMessageCodegen",
            AsteriaMessageCodegenExtension::class.java,
        )
        extension.asteriaVersion.convention(pluginVersion())

        var configured = false
        fun configureOnce() {
            if (configured) {
                return
            }
            configured = true
            project.pluginManager.apply(KSP_PLUGIN_ID)
            if (extension.addDependencies.get()) {
                addAsteriaDependencies(project, extension.asteriaVersion.get())
            }
            project.afterEvaluate {
                configureKsp(project, extension)
            }
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") { configureOnce() }
        project.pluginManager.withPlugin("kotlin") { configureOnce() }
    }

    private fun configureKsp(project: Project, extension: AsteriaMessageCodegenExtension) {
        project.extensions.configure(KspExtension::class.java) { ksp ->
            extension.generatedPackage.orNull?.let { generatedPackage ->
                ksp.arg("asteria.message.generated.package", generatedPackage)
            }
            extension.moduleId.orNull?.let { moduleId ->
                ksp.arg("asteria.message.module.id", moduleId)
            }
            ksp.arg("asteria.message.catalog.enabled", extension.messageCatalogEnabled.get().toString())
            extension.dispatcherSuperTypes.get().toSortedMap().forEach { (dispatcher, superType) ->
                ksp.arg("asteria.message.dispatcher.$dispatcher.superType", superType)
            }
        }
    }

    private fun addAsteriaDependencies(project: Project, version: String) {
        project.dependencies.addIfConfigurationExists(
            project,
            "implementation",
            project.localProjectOrCoordinate(":foundation:foundation-message", "foundation-message", version),
        )
        project.dependencies.add(
            "ksp",
            project.localProjectOrCoordinate(":foundation:foundation-message-ksp", "foundation-message-ksp", version),
        )
    }

    private fun org.gradle.api.artifacts.dsl.DependencyHandler.addIfConfigurationExists(
        project: Project,
        configurationName: String,
        dependencyNotation: Any,
    ) {
        if (project.configurations.findByName(configurationName) == null) {
            project.logger.warn(
                "Asteria message codegen skipped dependency $dependencyNotation because configuration " +
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
        return AsteriaMessageCodegenPlugin::class.java.`package`.implementationVersion
            ?: DEFAULT_ASTERIA_VERSION
    }

    companion object {
        const val DEFAULT_ASTERIA_VERSION = "1.0-SNAPSHOT"
        private const val ASTERIA_GROUP = "io.github.realm-labs.asteria"
        private const val KSP_PLUGIN_ID = "com.google.devtools.ksp"
    }
}
