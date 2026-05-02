package io.github.mikai233.asteria.config.ksp

import com.squareup.kotlinpoet.ClassName
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
                ),
                ConfigTableModel(
                    tableName = "daily_tasks",
                    keyType = INT,
                    rowType = TASK_CONFIG,
                    refName = "DailyTasks",
                    propertyName = "dailyTasks",
                ),
            ),
        )

        val code = file.toString()

        assertContains(code, "object GameConfigTables")
        assertContains(code, "val Items: ConfigTableRef<Int, ItemConfig> = configTableRef(\"items\")")
        assertContains(code, "val DailyTasks: ConfigTableRef<Int, TaskConfig> = configTableRef(\"daily_tasks\")")
        assertContains(code, "class GameConfigs(")
        assertContains(code, "val items: ConfigTable<Int, ItemConfig>")
        assertContains(code, "get() = configService.current().requireTable(GameConfigTables.Items)")
        assertContains(code, "fun ConfigSnapshot.items(): ConfigTable<Int, ItemConfig>")
        assertContains(code, "fun ConfigService.dailyTasks(): ConfigTable<Int, TaskConfig>")
    }

    private companion object {
        val INT = ClassName("kotlin", "Int")
        val ITEM_CONFIG = ClassName("com.example.config", "ItemConfig")
        val TASK_CONFIG = ClassName("com.example.config", "TaskConfig")
    }
}
