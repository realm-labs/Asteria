package io.github.realmlabs.asteria.config.publisher

import io.github.realmlabs.asteria.config.*
import io.github.realmlabs.asteria.config.center.InMemoryConfigStore
import io.github.realmlabs.asteria.config.center.JacksonConfigCodec
import io.github.realmlabs.asteria.config.center.RuntimeConfigRepository
import io.github.realmlabs.asteria.config.center.configPath
import io.github.realmlabs.asteria.config.luban.LubanBinaryConfigLoader
import io.github.realmlabs.asteria.config.luban.LubanSnapshotBridge
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ConfigPublisherTest {
    @Test
    fun `publishes validated artifacts manifest and current pointer`() = runBlocking {
        val dir = Files.createTempDirectory("asteria-config-publisher-test")
        dir.resolve("items.bytes").writeText("items")
        dir.resolve("activity").createDirectories()
        dir.resolve("activity/tasks.bytes").writeText("tasks")

        val store = InMemoryConfigStore()
        val layout = ConfigPublicationLayout(configPath("/game/config"))
        val revision = ConfigRevision("2026.05.02", "checksum-1")
        val publisher = ConfigPublisher(
            loader = {
                DefaultConfigSnapshot(
                    revision = revision,
                    tables = listOf(mapConfigTable("items", mapOf(1 to ItemConfig(1)))),
                )
            },
            artifactSource = DirectoryConfigArtifactSource(dir),
            store = store,
            layout = layout,
            validators = listOf(
                configValidator { snapshot ->
                    check(snapshot.tables().isNotEmpty(), "snapshot must contain at least one table")
                },
            ),
            componentBuilders = listOf(
                configComponentBuilder(
                    name = "item-index",
                    dependencies = setOf(ConfigTableName("items")),
                ) { snapshot ->
                    snapshot.tables().associate { it.name.value to it.size }
                },
            ),
            clock = Clock.fixed(Instant.parse("2026-05-02T00:00:00Z"), ZoneOffset.UTC),
        )

        val result = publisher.publish()
        val repository = RuntimeConfigRepository(store, JacksonConfigCodec())
        val manifest = repository.get<ConfigPublicationManifest>(layout.manifestPath(revision))?.value
        val current = repository.get<CurrentConfigPublication>(layout.currentPath)?.value
        val record = repository.get<ConfigPublicationRecord>(layout.historyRecordPath(revision))?.value

        assertEquals(revision, result.snapshot.revision)
        assertNotNull(manifest)
        assertEquals(revision, manifest.revision)
        assertEquals(listOf("items"), manifest.tables)
        assertEquals(listOf("activity/tasks.bytes", "items.bytes"), manifest.artifacts.map { it.path })
        assertEquals(listOf("item-index"), manifest.components.map { it.name })
        assertEquals(listOf("items"), manifest.components.single().dependencies)
        assertEquals(revision, current?.revision)
        assertEquals(layout.manifestPath(revision).value, current?.manifestPath)
        assertNotNull(record)
        assertEquals(revision, record.revision)
        assertEquals(2, record.artifactCount)
        assertEquals(10, record.totalArtifactBytes)
        assertNotNull(store.get(layout.artifactPath(revision, "items.bytes")))
        assertNotNull(store.get(layout.artifactPath(revision, "activity/tasks.bytes")))
    }

    @Test
    fun `consumer loads current publication as luban memory source`() = runBlocking {
        val store = InMemoryConfigStore()
        val layout = ConfigPublicationLayout(configPath("/game/config"))
        val revision = ConfigRevision("2026.05.02", "checksum-1")
        ConfigPublisher(
            loader = {
                DefaultConfigSnapshot(
                    revision = revision,
                    tables = listOf(mapConfigTable("items", mapOf(1 to ItemConfig(1)))),
                )
            },
            artifactSource = ConfigArtifactSource {
                listOf(ConfigPublicationArtifact("item_tbitem.bytes", "1:Sword\n2:Potion".toByteArray()))
            },
            store = store,
            layout = layout,
        ).publish()

        val bundle = ConfigPublicationConsumer(store, layout).loadCurrent()
        val snapshot = LubanBinaryConfigLoader(
            tablesType = FakeBinaryTables::class,
            dataSource = bundle.lubanDataSource(),
            bridge = FakeBinaryTablesBridge,
        ).load()

        assertEquals(revision, bundle.manifest.revision)
        assertEquals("Sword", snapshot.component<FakeBinaryTbItem>().get(1).name)
        assertEquals("Potion", snapshot.component<FakeBinaryTbItem>().get(2).name)
    }

    @Test
    fun `publication luban loader uses published revision`() = runBlocking {
        val store = InMemoryConfigStore()
        val layout = ConfigPublicationLayout(configPath("/game/config"))
        val revision = ConfigRevision("2026.05.02", "published-revision")
        ConfigPublisher(
            loader = {
                DefaultConfigSnapshot(
                    revision = revision,
                    tables = listOf(mapConfigTable("items", mapOf(1 to ItemConfig(1)))),
                )
            },
            artifactSource = ConfigArtifactSource {
                listOf(ConfigPublicationArtifact("item_tbitem.bytes", "1:Sword".toByteArray()))
            },
            store = store,
            layout = layout,
        ).publish()

        val snapshot = configPublicationLubanBinaryLoader<FakeBinaryTables>(
            store = store,
            bridge = FakeBinaryTablesBridge,
            layout = layout,
        ).load()

        assertEquals(revision, snapshot.revision)
        assertEquals("Sword", snapshot.component<FakeBinaryTbItem>().get(1).name)
    }

    @Test
    fun `publication reload trigger emits after current pointer moves`() = runBlocking {
        val store = InMemoryConfigStore()
        val layout = ConfigPublicationLayout(configPath("/game/config"))
        val revision = ConfigRevision("2026.05.02", "checksum-1")
        val signal = async {
            withTimeout(1_000) {
                configPublicationReloadTrigger(store, layout).events().first()
            }
        }
        yield()

        ConfigPublisher(
            loader = { DefaultConfigSnapshot(revision = revision) },
            artifactSource = ConfigArtifactSource {
                listOf(ConfigPublicationArtifact("items.bytes", "items".toByteArray()))
            },
            store = store,
            layout = layout,
        ).publish()

        val emitted = signal.await()
        assertEquals("config_center_upserted", emitted.reason)
        assertEquals(layout.currentPath.value, emitted.source)
    }

    @Test
    fun `publication operations list history and promote previous revision`() = runBlocking {
        val store = InMemoryConfigStore()
        val layout = ConfigPublicationLayout(configPath("/game/config"))
        val first = ConfigRevision("2026.05.02", "checksum-1")
        val second = ConfigRevision("2026.05.03", "checksum-2")

        publishRevision(store, layout, first, "1:Sword")
        publishRevision(store, layout, second, "1:Axe")

        val operations = ConfigPublicationOperations(
            store = store,
            layout = layout,
            clock = Clock.fixed(Instant.parse("2026-05-04T00:00:00Z"), ZoneOffset.UTC),
        )
        assertEquals(listOf(second, first), operations.history().map { it.revision })

        val current = operations.promote(first)

        assertEquals(first, current.revision)
        assertEquals(Instant.parse("2026-05-04T00:00:00Z"), current.publishedAt)
        assertEquals(first, ConfigPublicationConsumer(store, layout).loadCurrent().manifest.revision)
    }

    @Test
    fun `publication operations prune old non current revisions`() = runBlocking {
        val store = InMemoryConfigStore()
        val layout = ConfigPublicationLayout(configPath("/game/config"))
        val first = ConfigRevision("2026.05.02", "checksum-1")
        val second = ConfigRevision("2026.05.03", "checksum-2")
        val third = ConfigRevision("2026.05.04", "checksum-3")

        publishRevision(store, layout, first, "1:Sword")
        publishRevision(store, layout, second, "1:Axe")
        publishRevision(store, layout, third, "1:Bow")
        ConfigPublicationOperations(store, layout).promote(first)

        val result = ConfigPublicationOperations(store, layout).prune(retainLatest = 1)

        assertEquals(listOf(second), result.deletedRevisions)
        assertNotNull(store.get(layout.manifestPath(first)))
        assertNotNull(store.get(layout.artifactPath(first, "item_tbitem.bytes")))
        assertNotNull(store.get(layout.manifestPath(third)))
        assertNotNull(store.get(layout.artifactPath(third, "item_tbitem.bytes")))
        assertEquals(null, store.get(layout.manifestPath(second)))
        assertEquals(null, store.get(layout.artifactPath(second, "item_tbitem.bytes")))
        assertEquals(listOf(third, first), ConfigPublicationOperations(store, layout).history().map { it.revision })
    }

    @Test
    fun `consumer rejects corrupted artifact checksum`() = runBlocking {
        val store = InMemoryConfigStore()
        val layout = ConfigPublicationLayout(configPath("/game/config"))
        val revision = ConfigRevision("2026.05.02", "checksum-1")
        ConfigPublisher(
            loader = { DefaultConfigSnapshot(revision = revision) },
            artifactSource = ConfigArtifactSource {
                listOf(ConfigPublicationArtifact("items.bytes", "items".toByteArray()))
            },
            store = store,
            layout = layout,
        ).publish()
        store.put(layout.artifactPath(revision, "items.bytes"), "corrupted".toByteArray())

        assertFailsWith<ConfigPublicationValidationException> {
            ConfigPublicationConsumer(store, layout).loadCurrent()
        }
    }

    @Test
    fun `consumer rejects missing artifact`() = runBlocking {
        val store = InMemoryConfigStore()
        val layout = ConfigPublicationLayout(configPath("/game/config"))
        val revision = ConfigRevision("2026.05.02", "checksum-1")
        ConfigPublisher(
            loader = { DefaultConfigSnapshot(revision = revision) },
            artifactSource = ConfigArtifactSource {
                listOf(ConfigPublicationArtifact("items.bytes", "items".toByteArray()))
            },
            store = store,
            layout = layout,
        ).publish()
        store.delete(layout.artifactPath(revision, "items.bytes"))

        assertFailsWith<ConfigPublicationNotFoundException> {
            ConfigPublicationConsumer(store, layout).loadCurrent()
        }
    }

    @Test
    fun `consumer rejects current pointer with different manifest revision`() = runBlocking {
        val store = InMemoryConfigStore()
        val layout = ConfigPublicationLayout(configPath("/game/config"))
        val revision = ConfigRevision("2026.05.02", "checksum-1")
        val other = ConfigRevision("2026.05.03", "checksum-2")
        val repository = RuntimeConfigRepository(store, JacksonConfigCodec())
        repository.put(
            layout.manifestPath(revision),
            ConfigPublicationManifest(
                revision = revision,
                generatedAt = Instant.parse("2026-05-02T00:00:00Z"),
                tables = emptyList(),
                artifacts = emptyList(),
            ),
        )
        repository.put(
            layout.currentPath,
            CurrentConfigPublication(
                revision = other,
                manifestPath = layout.manifestPath(revision).value,
                publishedAt = Instant.parse("2026-05-02T00:00:00Z"),
            ),
        )

        assertFailsWith<ConfigPublicationValidationException> {
            ConfigPublicationConsumer(store, layout).loadCurrent()
        }
    }

    @Test
    fun `consumer rejects component dependency missing from manifest tables`() = runBlocking {
        val store = InMemoryConfigStore()
        val layout = ConfigPublicationLayout(configPath("/game/config"))
        val revision = ConfigRevision("2026.05.02", "checksum-1")
        val repository = RuntimeConfigRepository(store, JacksonConfigCodec())
        repository.put(
            layout.manifestPath(revision),
            ConfigPublicationManifest(
                revision = revision,
                generatedAt = Instant.parse("2026-05-02T00:00:00Z"),
                tables = listOf("items"),
                artifacts = emptyList(),
                components = listOf(
                    ConfigPublicationComponentManifest(
                        name = "activity-index",
                        type = "com.example.ActivityIndex",
                        dependencies = listOf("activities"),
                    ),
                ),
            ),
        )
        repository.put(
            layout.currentPath,
            CurrentConfigPublication(
                revision = revision,
                manifestPath = layout.manifestPath(revision).value,
                publishedAt = Instant.parse("2026-05-02T00:00:00Z"),
            ),
        )

        val error = assertFailsWith<ConfigPublicationValidationException> {
            ConfigPublicationConsumer(store, layout).loadCurrent()
        }

        assertEquals(
            "config component activity-index depends on missing tables: activities",
            error.message,
        )
    }

    @Test
    fun `does not publish when validation fails`() = runBlocking {
        val store = InMemoryConfigStore()
        val layout = ConfigPublicationLayout(configPath("/game/config"))
        val publisher = ConfigPublisher(
            loader = {
                DefaultConfigSnapshot(
                    revision = ConfigRevision("bad"),
                    tables = emptyList(),
                )
            },
            artifactSource = ConfigArtifactSource { listOf(ConfigPublicationArtifact("items.bytes", byteArrayOf(1))) },
            store = store,
            layout = layout,
            validators = listOf(
                configValidator { snapshot ->
                    check(snapshot.tables().isNotEmpty(), "snapshot must contain at least one table")
                },
            ),
        )

        assertFailsWith<ConfigValidationException> {
            publisher.publish()
        }
        assertEquals(null, store.get(layout.currentPath))
    }
}

private suspend fun publishRevision(
    store: InMemoryConfigStore,
    layout: ConfigPublicationLayout,
    revision: ConfigRevision,
    rows: String,
) {
    ConfigPublisher(
        loader = {
            DefaultConfigSnapshot(
                revision = revision,
                tables = listOf(mapConfigTable("items", mapOf(1 to ItemConfig(1)))),
            )
        },
        artifactSource = ConfigArtifactSource {
            listOf(ConfigPublicationArtifact("item_tbitem.bytes", rows.toByteArray()))
        },
        store = store,
        layout = layout,
    ).publish()
}

private data class ItemConfig(
    val id: Int,
)

class FakeBinaryTables(loader: IByteBufLoader) {
    private val tbItem = FakeBinaryTbItem(loader.load("item_tbitem"))

    fun getTbItem(): FakeBinaryTbItem = tbItem

    fun interface IByteBufLoader {
        fun load(file: String): FakeByteBuf
    }
}

class FakeByteBuf(
    val bytes: ByteArray,
)

class FakeBinaryTbItem(byteBuf: FakeByteBuf) {
    private val rows: Map<Int, FakeBinaryItemConfig> = byteBuf.bytes
        .decodeToString()
        .lineSequence()
        .filter { it.isNotBlank() }
        .associate { line ->
            val parts = line.split(":", limit = 2)
            val item = FakeBinaryItemConfig(parts[0].toInt(), parts[1])
            item.id to item
        }

    fun get(id: Int): FakeBinaryItemConfig {
        return rows.getValue(id)
    }
}

data class FakeBinaryItemConfig(
    val id: Int,
    val name: String,
)

private object FakeBinaryTablesBridge : LubanSnapshotBridge<FakeBinaryTables, FakeBinaryTables.IByteBufLoader> {
    override val loaderType = FakeBinaryTables.IByteBufLoader::class

    override fun createTables(loader: FakeBinaryTables.IByteBufLoader): FakeBinaryTables {
        return FakeBinaryTables(loader)
    }

    override fun buildEntries(tables: FakeBinaryTables): List<SnapshotEntry> {
        return listOf(SnapshotEntry.Component(tables.getTbItem(), FakeBinaryTbItem::class))
    }
}
