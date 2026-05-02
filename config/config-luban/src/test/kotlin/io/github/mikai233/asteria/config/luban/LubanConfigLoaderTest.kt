package io.github.mikai233.asteria.config.luban

import com.fasterxml.jackson.databind.JsonNode
import io.github.mikai233.asteria.config.ConfigService
import io.github.mikai233.asteria.config.requireComponent
import io.github.mikai233.asteria.core.gameApplication
import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LubanConfigLoaderTest {
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
            dataSource = DirectoryLubanDataSource(dataDir),
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

        val loader = LubanJsonConfigLoader(FakeTables::class, DirectoryLubanDataSource(dataDir))
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

    @Test
    fun `loads generated binary tables with bytebuf loader proxy`() = runBlocking {
        val dataDir = Files.createTempDirectory("asteria-luban-bin")
        dataDir.resolve("item_tbitem.bytes").writeBytes("1:Sword\n2:Potion".toByteArray())

        val snapshot = LubanBinaryConfigLoader(
            tablesType = FakeBinaryTables::class,
            dataSource = DirectoryLubanDataSource(dataDir),
        ).load()

        val tables = snapshot.requireComponent<FakeBinaryTables>()
        val items = snapshot.requireComponent<FakeBinaryTbItem>()
        assertEquals("Sword", tables.getTbItem().get(1).name)
        assertEquals("Potion", items.get(2).name)
    }

    @Test
    fun `loads generated binary tables from memory source`() = runBlocking {
        val snapshot = LubanBinaryConfigLoader(
            tablesType = FakeBinaryTables::class,
            dataSource = MemoryLubanDataSource(
                mapOf("item_tbitem.bytes" to "1:Sword\n2:Potion".toByteArray()),
            ),
        ).load()

        val items = snapshot.requireComponent<FakeBinaryTbItem>()
        assertEquals("Sword", items.get(1).name)
        assertEquals("Potion", items.get(2).name)
    }

    @Test
    fun `module can load binary config`() = runBlocking {
        val dataDir = Files.createTempDirectory("asteria-luban-bin")
        dataDir.resolve("item_tbitem.bytes").writeBytes("1:Sword".toByteArray())

        val app = gameApplication {
            install(
                LubanConfigModule {
                    binary()
                    preload(maxConcurrency = 2)
                    tables<FakeBinaryTables>()
                    dataDir(dataDir)
                },
            )
        }

        app.launch()

        val items = app.services.get<ConfigService>()
            .current()
            .requireComponent<FakeBinaryTbItem>()
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

    class FakeBinaryTables(loader: IByteBufLoader) {
        private val tbItem = FakeBinaryTbItem(loader.load("item_tbitem"))

        fun getTbItem(): FakeBinaryTbItem = tbItem

        fun interface IByteBufLoader {
            @Throws(IOException::class)
            fun load(file: String): FakeByteBuf
        }
    }

    class FakeByteBuf(
        val bytes: ByteArray,
    )

    class FakeBinaryTbItem(byteBuf: FakeByteBuf) {
        private val rows: Map<Int, Item> = byteBuf.bytes
            .decodeToString()
            .lineSequence()
            .filter { it.isNotBlank() }
            .associate { line ->
                val parts = line.split(":", limit = 2)
                val item = Item(
                    id = parts[0].toInt(),
                    name = parts[1],
                )
                item.id to item
            }

        fun get(id: Int): Item {
            return rows.getValue(id)
        }
    }
}
