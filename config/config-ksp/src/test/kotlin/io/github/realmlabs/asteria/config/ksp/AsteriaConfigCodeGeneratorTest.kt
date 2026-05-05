package io.github.realmlabs.asteria.config.ksp

import com.squareup.kotlinpoet.ClassName
import io.github.realmlabs.asteria.config.annotations.AsteriaConfigTableShape
import kotlin.test.Test
import kotlin.test.assertContains

class AsteriaConfigCodeGeneratorTest {
    @Test
    fun `generates table refs dynamic accessors and extensions`() {
        val file = AsteriaConfigCodeGenerator.buildFile(
            config = ConfigCodegenConfig(
                packageName = "com.example.generated",
                tablesObjectName = "GameConfigTables",
                accessorClassName = "GameConfigs",
            ),
            tables = listOf(
                ConfigTableModel(
                    tableName = "items",
                    keyType = INT,
                    rowType = ITEM_CONFIG,
                    tableType = ConfigAccessorTableType(MAP_CONFIG_TABLE, typeArgumentCount = 2),
                ),
                ConfigTableModel(
                    tableName = "daily_tasks",
                    keyType = INT,
                    rowType = TASK_CONFIG,
                    refName = "DailyTasks",
                    propertyName = "dailyTasks",
                ),
                ConfigTableModel(
                    tableName = "rank_rewards",
                    shape = AsteriaConfigTableShape.LIST,
                    rowType = ITEM_CONFIG,
                    refName = "RankRewards",
                    propertyName = "rankRewards",
                ),
                ConfigTableModel(
                    tableName = "global",
                    shape = AsteriaConfigTableShape.SINGLETON,
                    rowType = ITEM_CONFIG,
                    refName = "Global",
                    propertyName = "global",
                ),
            ),
        )

        val code = file.toString()

        assertContains(code, "object GameConfigTables")
        assertContains(code, "val Items: ConfigTableRef<Int, ItemConfig> = configTableRef(\"items\")")
        assertContains(code, "val DailyTasks: ConfigTableRef<Int, TaskConfig> = configTableRef(\"daily_tasks\")")
        assertContains(code, "val RankRewards: RowConfigTableRef<ItemConfig> = rowConfigTableRef(\"rank_rewards\")")
        assertContains(code, "val Global: RowConfigTableRef<ItemConfig> = rowConfigTableRef(\"global\")")
        assertContains(code, "class GameConfigs(")
        assertContains(code, "val items: MapConfigTable<Int, ItemConfig>")
        assertContains(code, "get() = configService.current().requireTable(GameConfigTables.Items, MapConfigTable::class)")
        assertContains(code, "fun ConfigSnapshot.items(): MapConfigTable<Int, ItemConfig>")
        assertContains(code, "= requireTable(GameConfigTables.Items, MapConfigTable::class)")
        assertContains(code, "fun ConfigService.items(): MapConfigTable<Int, ItemConfig>")
        assertContains(code, "= current().requireTable(GameConfigTables.Items, MapConfigTable::class)")
        assertContains(code, "fun ConfigService.dailyTasks(): KeyedConfigTable<Int, TaskConfig>")
        assertContains(code, "val rankRewards: ListConfigTable<ItemConfig>")
        assertContains(code, "get() = configService.current().requireListTable(GameConfigTables.RankRewards)")
        assertContains(code, "val global: SingleConfigTable<ItemConfig>")
        assertContains(code, "get() = configService.current().requireSingleTable(GameConfigTables.Global)")
        assertContains(code, "fun ConfigService.rankRewards(): ListConfigTable<ItemConfig>")
        assertContains(code, "fun ConfigSnapshot.global(): SingleConfigTable<ItemConfig>")
    }

    @Test
    fun `splits large table extension lists into chunk files`() {
        val files = AsteriaConfigCodeGenerator.buildFiles(
            config = ConfigCodegenConfig(
                packageName = "com.example.generated",
                tablesObjectName = "GameConfigTables",
                accessorClassName = "GameConfigs",
            ),
            tables = (0..200).map { index ->
                ConfigTableModel(
                    tableName = "table_$index",
                    keyType = INT,
                    rowType = ITEM_CONFIG,
                    refName = "Table$index",
                    propertyName = "table$index",
                )
            },
        )

        val fileNames = files.map { it.fileName }
        val main = files.first { it.fileName == "GameConfigs" }.file.toString()
        val chunk0 = files.first { it.fileName == "GameConfigsExtensionsChunk0" }.file.toString()
        val chunk1 = files.first { it.fileName == "GameConfigsExtensionsChunk1" }.file.toString()

        assertContains(fileNames, "GameConfigs")
        assertContains(fileNames, "GameConfigsExtensionsChunk0")
        assertContains(fileNames, "GameConfigsExtensionsChunk1")
        assertContains(main, "object GameConfigTables")
        assertContains(main, "class GameConfigs(")
        assertContains(chunk0, "fun ConfigSnapshot.table")
        assertContains(chunk1, "fun ConfigService.table")
    }

    private companion object {
        val INT = ClassName("kotlin", "Int")
        val ITEM_CONFIG = ClassName("com.example.config", "ItemConfig")
        val TASK_CONFIG = ClassName("com.example.config", "TaskConfig")
        val MAP_CONFIG_TABLE = ClassName("io.github.realmlabs.asteria.config", "MapConfigTable")
    }
}
