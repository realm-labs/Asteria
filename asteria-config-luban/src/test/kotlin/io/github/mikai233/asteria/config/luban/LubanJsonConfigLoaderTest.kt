package io.github.mikai233.asteria.config.luban

import com.fasterxml.jackson.databind.JsonNode
import io.github.mikai233.asteria.config.ConfigService
import io.github.mikai233.asteria.config.requireComponent
import io.github.mikai233.asteria.core.gameApplication
import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LubanJsonConfigLoaderTest {
    @Test
    fun `loads generated tables with json loader proxy`() = runBlocking {
        val dataDir = Files.createTempDirectory("asteria-luban-json")
        dataDir.resolve("item_tbitem.json").writeText(
            """
            [
              {"id": 1, "name": "Sword"},
              {"id": 2, "name": "Potion"}
            ]
            """.trimIndent(),
        )

        val snapshot = LubanJsonConfigLoader(
            tablesType = FakeTables::class,
            dataDir = dataDir,
        ).load()

        val tables = snapshot.requireComponent<FakeTables>()
        val items = snapshot.requireComponent<FakeTbItem>()
        assertEquals("Sword", tables.getTbItem().get(1).name)
        assertEquals("Potion", items.get(2).name)
        assertEquals(1, snapshot.components().count { it is FakeTables })
    }

    @Test
    fun `checksum changes when source json changes`() = runBlocking {
        val dataDir = Files.createTempDirectory("asteria-luban-json")
        val file = dataDir.resolve("item_tbitem.json")
        file.writeText("""[{"id": 1, "name": "Sword"}]""")

        val loader = LubanJsonConfigLoader(FakeTables::class, dataDir)
        val first = loader.load()

        file.writeText("""[{"id": 1, "name": "Axe"}]""")
        val second = loader.load()

        assertNotEquals(first.revision.checksum, second.revision.checksum)
        assertEquals("Axe", second.requireComponent<FakeTbItem>().get(1).name)
    }

    @Test
    fun `module registers config service and loads on start`() = runBlocking {
        val dataDir = Files.createTempDirectory("asteria-luban-json")
        dataDir.resolve("item_tbitem.json").writeText("""[{"id": 1, "name": "Sword"}]""")

        val app = gameApplication {
            install(
                LubanConfigModule {
                    tables<FakeTables>()
                    dataDir(dataDir)
                },
            )
        }

        app.launch()

        val items = app.services.get<ConfigService>()
            .current()
            .requireComponent<FakeTbItem>()
        assertEquals("Sword", items.get(1).name)

        app.stop()
    }

    class FakeTables(loader: IJsonLoader) {
        private val tbItem = FakeTbItem(loader.load("item_tbitem"))

        fun getTbItem(): FakeTbItem = tbItem

        fun interface IJsonLoader {
            @Throws(IOException::class)
            fun load(file: String): JsonNode
        }
    }

    class FakeTbItem(json: JsonNode) {
        private val rows: Map<Int, Item> = json.associate { obj ->
            val item = Item(
                id = obj.get("id").asInt(),
                name = obj.get("name").asText(),
            )
            item.id to item
        }

        fun get(id: Int): Item {
            return rows.getValue(id)
        }
    }

    data class Item(
        val id: Int,
        val name: String,
    )
}
