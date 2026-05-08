package io.github.realmlabs.asteria.config.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testfixtures.ProjectBuilder
import java.nio.file.Path as NioPath
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AsteriaConfigCodegenPluginTest {
    @Test
    fun `plugin registers extension with defaults`() {
        val project = ProjectBuilder.builder().build()

        project.pluginManager.apply(AsteriaConfigCodegenPlugin::class.java)

        val extension = project.extensions.findByType(AsteriaConfigCodegenExtension::class.java)
        assertNotNull(extension)
        assertEquals("io.github.realmlabs.asteria.generated.config", extension.packageName.get())
        assertEquals("GameConfigTables", extension.tablesObjectName.get())
        assertEquals("GameConfigs", extension.accessorClassName.get())
        assertEquals("", extension.configChange.receiverType.get())
        assertEquals("GeneratedConfigChangeHandlers", extension.configChange.className.get())
        assertEquals(true, extension.addDependencies.get())
    }

    @Test
    fun `Luban marker generator emits catalog and table markers`() {
        val source = AsteriaLubanConfigMarkerGenerator.buildSource(
            config = LubanMarkerGeneratorConfig(
                outputDirectory = Path("build/generated"),
                packageName = "com.example.generated",
                fileName = "GeneratedLubanMarkers",
                tablesObjectName = "GameConfigTables",
                accessorClassName = "GameConfigs",
            ),
            tables = listOf(
                LubanConfigTableSpec(
                    name = "items",
                    keyType = "kotlin.Int",
                    rowType = "cfg.item.ItemConfig",
                    tableType = "io.github.realmlabs.asteria.config.MapConfigTable",
                    refName = "Items",
                    propertyName = "items",
                ),
                LubanConfigTableSpec(
                    name = "rank_rewards",
                    shape = LubanConfigTableShape.LIST,
                    rowType = "cfg.item.ItemConfig",
                    refName = "RankRewards",
                    propertyName = "rankRewards",
                ),
                LubanConfigTableSpec(
                    name = "global",
                    shape = LubanConfigTableShape.SINGLETON,
                    rowType = "cfg.item.ItemConfig",
                    refName = "Global",
                    propertyName = "global",
                ),
            ),
        )

        assertContains(source, "package com.example.generated")
        assertContains(source, "@AsteriaConfigCatalog(")
        assertContains(source, "tablesObjectName = \"GameConfigTables\"")
        assertContains(source, "@AsteriaConfigTable(")
        assertContains(source, "name = \"items\"")
        assertContains(source, "keyType = kotlin.Int::class")
        assertContains(source, "rowType = cfg.item.ItemConfig::class")
        assertContains(source, "tableType = io.github.realmlabs.asteria.config.MapConfigTable::class")
        assertContains(source, "object ItemsTable")
        assertContains(source, "shape = AsteriaConfigTableShape.LIST")
        assertContains(source, "object RankRewardsTable")
        assertContains(source, "shape = AsteriaConfigTableShape.SINGLETON")
        assertContains(source, "object GlobalTable")
    }

    @Test
    fun `Luban marker generator splits large table marker files`() {
        val sources = AsteriaLubanConfigMarkerGenerator.buildSources(
            config = LubanMarkerGeneratorConfig(
                outputDirectory = Path("build/generated"),
                packageName = "com.example.generated",
                fileName = "GeneratedLubanMarkers",
                tablesObjectName = "GameConfigTables",
                accessorClassName = "GameConfigs",
            ),
            tables = (0..200).map { index ->
                LubanConfigTableSpec(
                    name = "table_$index",
                    keyType = "kotlin.Int",
                    rowType = "cfg.item.ItemConfig",
                    markerName = "Table${index}Marker",
                )
            },
        )

        val fileNames = sources.map { it.fileName }
        val catalog = sources.first { it.fileName == "GeneratedLubanMarkers" }.source
        val chunk0 = sources.first { it.fileName == "GeneratedLubanMarkersChunk0" }.source
        val chunk1 = sources.first { it.fileName == "GeneratedLubanMarkersChunk1" }.source

        assertContains(fileNames, "GeneratedLubanMarkers")
        assertContains(fileNames, "GeneratedLubanMarkersChunk0")
        assertContains(fileNames, "GeneratedLubanMarkersChunk1")
        assertContains(catalog, "@AsteriaConfigCatalog(")
        assertContains(chunk0, "@AsteriaConfigTable(")
        assertContains(chunk1, "@AsteriaConfigTable(")
    }

    @Test
    fun `Luban marker task removes stale chunk outputs before regenerating`() {
        val projectDir = createTempDirectory("asteria-config-marker-task")
        val project = ProjectBuilder.builder()
            .withProjectDir(projectDir.toFile())
            .build()
        val metadataFile = projectDir.resolve("asteria-config-tables.json")
        val outputDirectory = projectDir.resolve("build/generated/asteria/lubanConfigMarkers")
        val generatedPackageDirectory = outputDirectory.resolve(Path("com/example/generated"))
        val task = project.tasks.register(
            "generateAsteriaLubanConfigMarkers",
            AsteriaLubanConfigMarkerTask::class.java,
        ).get()
        task.generationEnabled.set(true)
        task.metadataFile.set(project.layout.projectDirectory.file("asteria-config-tables.json"))
        task.outputDirectory.set(project.layout.projectDirectory.dir("build/generated/asteria/lubanConfigMarkers"))
        task.packageName.set("com.example.generated")
        task.fileName.set("GeneratedLubanMarkers")
        task.tablesObjectName.set("GameConfigTables")
        task.accessorClassName.set("GameConfigs")

        metadataFile.writeText(markerMetadata(201))
        task.generate()

        assertTrue(generatedPackageDirectory.resolve("GeneratedLubanMarkersChunk0.kt").exists())
        assertTrue(generatedPackageDirectory.resolve("GeneratedLubanMarkersChunk1.kt").exists())

        metadataFile.writeText(markerMetadata(1))
        task.generate()

        val singleSource = generatedPackageDirectory.resolve("GeneratedLubanMarkers.kt")
        assertTrue(singleSource.exists())
        assertFalse(generatedPackageDirectory.resolve("GeneratedLubanMarkersChunk0.kt").exists())
        assertFalse(generatedPackageDirectory.resolve("GeneratedLubanMarkersChunk1.kt").exists())
        assertContains(singleSource.readText(), "object Table0Marker")
    }

    @Test
    fun `compileKotlin depends on Luban marker generation`() {
        val projectDir = createTempDirectory("asteria-config-marker-compile")
        writeMarkerBuild(projectDir)
        projectDir.resolve("asteria-config-tables.json").writeText(markerMetadata(1))

        val result = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("compileKotlin", "--dry-run", "--stacktrace")
            .build()

        assertContains(result.output, ":generateAsteriaLubanConfigMarkers SKIPPED")
        assertContains(result.output, ":compileKotlin SKIPPED")
        val markerIndex = result.output.indexOf(":generateAsteriaLubanConfigMarkers SKIPPED")
        val compileIndex = result.output.indexOf(":compileKotlin SKIPPED")
        assertTrue(markerIndex < compileIndex)
    }

    @Test
    fun `Luban marker task is up-to-date when metadata is unchanged`() {
        val projectDir = createTempDirectory("asteria-config-marker-incremental")
        writeMarkerBuild(projectDir)
        projectDir.resolve("asteria-config-tables.json").writeText(markerMetadata(1))

        val first = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("generateAsteriaLubanConfigMarkers", "--stacktrace")
            .build()
        val second = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("generateAsteriaLubanConfigMarkers", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, first.task(":generateAsteriaLubanConfigMarkers")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":generateAsteriaLubanConfigMarkers")?.outcome)
    }

    private fun markerMetadata(tableCount: Int): String {
        val tables = (0 until tableCount).joinToString(",\n") { index ->
            """
            {
              "name": "table_$index",
              "keyType": "kotlin.Int",
              "rowType": "cfg.item.ItemConfig",
              "markerName": "Table${index}Marker"
            }
            """.trimIndent()
        }
        return """
        {
          "tables": [
        $tables
          ]
        }
        """.trimIndent()
    }

    private fun writeMarkerBuild(projectDir: NioPath) {
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    mavenCentral()
                }
            }
            rootProject.name = "config-marker-test"
            """.trimIndent(),
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm") version "2.3.21"
                id("io.github.realm-labs.asteria.config-codegen")
            }

            asteriaConfigCodegen {
                addDependencies.set(false)
                packageName.set("com.example.generated")

                luban {
                    enabled.set(true)
                    metadataFile.set(layout.projectDirectory.file("asteria-config-tables.json"))
                    fileName.set("GeneratedLubanMarkers")
                }
            }
            """.trimIndent(),
        )
    }
}
