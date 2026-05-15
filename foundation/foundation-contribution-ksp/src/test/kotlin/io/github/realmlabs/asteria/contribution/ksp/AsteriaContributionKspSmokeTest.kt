package io.github.realmlabs.asteria.contribution.ksp

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains

class AsteriaContributionKspSmokeTest {
    @Test
    fun directAnnotatedComplexServicesAreCollected() {
        val projectDir = Files.createTempDirectory("asteria-contribution-ksp-smoke")
        val runtimeClasspath = currentRuntimeClasspath()
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    mavenCentral()
                }
            }
            rootProject.name = "asteria-contribution-ksp-smoke"
            """.trimIndent(),
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm") version "2.3.21"
                id("com.google.devtools.ksp") version "2.3.7"
            }

            val asteriaTestClasspath = files(${runtimeClasspath.toGradleFilesArgument()})

            dependencies {
                implementation(asteriaTestClasspath)
                ksp(asteriaTestClasspath)
            }

            kotlin {
                jvmToolchain(21)
            }
            """.trimIndent(),
        )
        projectDir.resolve("src/main/kotlin/sample").createDirectories()
        projectDir.resolve("src/main/kotlin/sample/Services.kt").writeText(
            """
            package sample

            import io.github.realmlabs.asteria.contribution.AsteriaContribution

            interface PlayerPatchableService

            data class GeneratedMessage(val id: Long)
            data class TrackedEntity(val id: Long)
            data class BusinessEnvelope<T>(val value: T)

            @AsteriaContribution(PlayerPatchableService::class)
            class LoginService : PlayerPatchableService {
                fun handle(message: GeneratedMessage): BusinessEnvelope<TrackedEntity> =
                    BusinessEnvelope(TrackedEntity(message.id))
            }

            @AsteriaContribution(PlayerPatchableService::class)
            class CdKeyService : PlayerPatchableService {
                fun redeem(request: Map<String, List<GeneratedMessage>>): Set<TrackedEntity> =
                    request.values.flatten().mapTo(linkedSetOf()) { TrackedEntity(it.id) }
            }

            @AsteriaContribution(PlayerPatchableService::class)
            object SettingService : PlayerPatchableService
            """.trimIndent(),
        )

        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withArguments("kspKotlin", "--stacktrace")
            .forwardOutput()
            .build()

        val catalog = projectDir
            .resolve("build/generated/ksp/main/kotlin/sample/GeneratedPlayerPatchableServiceContributions.kt")
            .toFile()
            .readText()

        assertContains(catalog, "LoginService::class")
        assertContains(catalog, "CdKeyService::class")
        assertContains(catalog, "SettingService::class")
    }

    private fun currentRuntimeClasspath(): List<File> {
        return System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .map(::File)
            .filter { it.exists() }
            .distinctBy { it.absolutePath }
    }

    private fun List<File>.toGradleFilesArgument(): String {
        return joinToString(", ") { file ->
            "\"${file.absolutePath.replace("\\", "\\\\")}\""
        }
    }
}
