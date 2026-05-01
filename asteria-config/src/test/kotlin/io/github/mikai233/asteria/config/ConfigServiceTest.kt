package io.github.mikai233.asteria.config

import io.github.mikai233.asteria.core.gameApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConfigServiceTest {
    @Test
    fun `reload loads typed tables and notifies listeners`() = runBlocking {
        val loader = TestConfigLoader()
        val service = ConfigService(loader)
        var notified: ConfigReloadResult? = null
        service.subscribe { result -> notified = result }

        val result = service.reload()
        val table = result.current.requireTable<Int, ItemConfig>(ConfigTableName("items"))

        assertNull(result.previous)
        assertEquals("v1", result.current.revision.version)
        assertEquals("Sword", table.require(1).name)
        assertEquals(2, table.size)
        assertEquals("project-configs", result.current.requireComponent<GeneratedTables>().name)
        assertEquals(result, notified)
    }

    @Test
    fun `reload rejects invalid snapshot`() = runBlocking {
        val service = ConfigService(
            loader = TestConfigLoader(),
            validators = listOf(
                configValidator { snapshot ->
                    val items = snapshot.requireTable<Int, ItemConfig>(ConfigTableName("items"))
                    items.all().forEach { item ->
                        check(item.price > 100, "price must be greater than 100", items.name, item.id)
                    }
                },
            ),
        )

        val error = assertFailsWith<ConfigValidationException> {
            service.reload()
        }

        assertEquals(2, error.errors.size)
        assertNull(service.currentOrNull())
    }

    @Test
    fun `module registers service and loads on start`() = runBlocking {
        val app = gameApplication {
            install(
                ConfigModule {
                    loader(TestConfigLoader())
                },
            )
        }

        app.launch()

        val service = app.services.get<ConfigService>()
        val items = service.current().requireTable<Int, ItemConfig>(ConfigTableName("items"))
        assertNotNull(items[1])

        app.stop()
    }

    private data class ItemConfig(
        val id: Int,
        val name: String,
        val price: Int,
    )

    private class TestConfigLoader : ConfigLoader {
        override suspend fun load(): ConfigSnapshot {
            return DefaultConfigSnapshot(
                revision = ConfigRevision("v1"),
                tables = listOf(
                    mapConfigTable(
                        name = "items",
                        rows = listOf(
                            ItemConfig(1, "Sword", 10),
                            ItemConfig(2, "Potion", 5),
                        ).associateBy { it.id },
                    ),
                ),
                components = listOf(GeneratedTables("project-configs")),
            )
        }
    }

    private data class GeneratedTables(
        val name: String,
    )
}
