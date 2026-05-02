package io.github.mikai233.asteria.config.gradle

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
        assertEquals("io.github.mikai233.asteria.generated.config", extension.packageName.get())
        assertEquals("GameConfigTables", extension.tablesObjectName.get())
        assertEquals("GameConfigs", extension.accessorClassName.get())
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
    }
}
