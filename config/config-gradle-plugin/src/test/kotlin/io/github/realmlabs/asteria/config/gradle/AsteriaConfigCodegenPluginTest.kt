package io.github.realmlabs.asteria.config.gradle

import org.gradle.testfixtures.ProjectBuilder
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
}
