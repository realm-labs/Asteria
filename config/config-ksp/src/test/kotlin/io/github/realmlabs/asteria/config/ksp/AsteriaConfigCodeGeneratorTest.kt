package io.github.realmlabs.asteria.config.ksp

import com.squareup.kotlinpoet.ClassName
import io.github.realmlabs.asteria.config.annotations.AsteriaConfigTableShape
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

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
        assertContains(code, ") : ConfigSnapshot")
        assertContains(code, "private val configService: ConfigService")
        assertContains(code, "private val current: ConfigSnapshot")
        assertContains(code, "override val revision: ConfigRevision")
        assertContains(code, "override fun table(name: ConfigTableName): ConfigTable<*>?")
        assertContains(code, "override fun <T : ConfigTable<*>> table(type: KClass<T>): T?")
        assertContains(code, "override fun tables(): Collection<ConfigTable<*>>")
        assertContains(code, "override fun <T : Any> component(type: KClass<T>): T?")
        assertContains(code, "override fun components(): Collection<Any>")
        assertContains(code, "get() = configService.current()")
        assertContains(code, "= current.component(type)")
        assertFalse(code.contains("get() = configService.current().requireTable("))
        assertFalse(code.contains("get() = configService.current().requireListTable("))
        assertFalse(code.contains("get() = configService.current().requireSingleTable("))
        assertContains(code, "val ConfigSnapshot.items: MapConfigTable<Int, ItemConfig>")
        assertContains(code, "get() = requireTable(GameConfigTables.Items, MapConfigTable::class)")
        assertContains(code, "val ConfigService.items: MapConfigTable<Int, ItemConfig>")
        assertContains(code, "get() = current().requireTable(GameConfigTables.Items, MapConfigTable::class)")
        assertContains(code, "val ConfigService.dailyTasks: KeyedConfigTable<Int, TaskConfig>")
        assertContains(code, "val ConfigService.rankRewards: ListConfigTable<ItemConfig>")
        assertContains(code, "val ConfigSnapshot.global: SingleConfigTable<ItemConfig>")
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
        assertContains(main, ") : ConfigSnapshot")
        assertContains(main, "override fun <T : Any> component(type: KClass<T>): T?")
        assertFalse(main.contains("get() = configService.current().requireTable("))
        assertContains(chunk0, "val ConfigSnapshot.table")
        assertContains(chunk1, "val ConfigService.table")
    }

    @Test
    fun `uses single accessor file when table count drops below chunk threshold`() {
        val files = AsteriaConfigCodeGenerator.buildFiles(
            config = ConfigCodegenConfig(
                packageName = "com.example.generated",
                tablesObjectName = "GameConfigTables",
                accessorClassName = "GameConfigs",
            ),
            tables = listOf(
                ConfigTableModel(
                    tableName = "table_0",
                    keyType = INT,
                    rowType = ITEM_CONFIG,
                    refName = "Table0",
                    propertyName = "table0",
                ),
            ),
        )

        val fileNames = files.map { it.fileName }
        val main = files.single { it.fileName == "GameConfigs" }.file.toString()

        assertFalse(fileNames.any { it.startsWith("GameConfigsExtensionsChunk") })
        assertContains(main, "object GameConfigTables")
        assertContains(main, "class GameConfigs(")
        assertContains(main, ") : ConfigSnapshot")
        assertFalse(main.contains("get() = configService.current().requireTable("))
        assertContains(main, "val ConfigSnapshot.table0")
        assertContains(main, "val ConfigService.table0")
    }

    private companion object {
        val INT = ClassName("kotlin", "Int")
        val ITEM_CONFIG = ClassName("com.example.config", "ItemConfig")
        val TASK_CONFIG = ClassName("com.example.config", "TaskConfig")
        val MAP_CONFIG_TABLE = ClassName("io.github.realmlabs.asteria.config", "MapConfigTable")
    }
}
