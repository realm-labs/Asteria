package io.github.realmlabs.asteria.gm.config

import io.github.realmlabs.asteria.config.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class SnapshotGmConfigInspectorTest {
    @Test
    fun inspectorListsAndDescribesLoadedTables() = runBlocking {
        val inspector = inspector()

        val metadata = inspector.metadata()
        val tables = inspector.listTables()
        val descriptor = inspector.describeTable(ConfigTableName("items"))

        assertEquals("v1", metadata.revision.version)
        assertEquals(1, metadata.tableCount)
        assertEquals(listOf("items"), tables.map { it.name })
        assertEquals(3, descriptor.size)
        assertTrue(descriptor.fields.any { it.name == "name" })
        assertTrue(descriptor.fields.any { it.name == "reward" })
    }

    @Test
    fun inspectorCanFindRowsByStringId() = runBlocking {
        val inspector = inspector()

        val row = inspector.findRow(ConfigTableName("items"), "1")

        assertNotNull(row)
        assertEquals("Sword", row.values["name"])
        assertEquals(mapOf("itemId" to 1001, "count" to 1), row.values["reward"])
        assertNull(inspector.findRow(ConfigTableName("items"), "404"))
    }

    @Test
    fun inspectorCanQueryRowsByKeywordAndFieldFilters() = runBlocking {
        val inspector = inspector()

        val keyword = inspector.queryRows(ConfigTableName("items"), GmConfigRowQuery(keyword = "potion"))
        val filtered = inspector.queryRows(
            ConfigTableName("items"),
            GmConfigRowQuery(
                filters = listOf(
                    GmConfigRowFilter("quality", GmConfigFilterOperator.Eq, "5"),
                ),
            ),
        )

        assertEquals(listOf("2"), keyword.rows.map { it.id })
        assertEquals(listOf("1"), filtered.rows.map { it.id })
    }

    @Test
    fun inspectorPaginatesRows() = runBlocking {
        val inspector = inspector()

        val page = inspector.queryRows(ConfigTableName("items"), GmConfigRowQuery(offset = 1, limit = 1))

        assertEquals(3, page.total)
        assertEquals(listOf("2"), page.rows.map { it.id })
        assertEquals(2, page.nextOffset)
    }

    @Test
    fun inspectorReportsReloadStatusAndManualReload() = runBlocking {
        val loader = VersionedConfigLoader()
        val service = ConfigService(loader)
        val monitor = ConfigReloadMonitor()
        service.subscribe(monitor)
        service.load()
        val inspector = SnapshotGmConfigInspector(service, reloadMonitor = monitor)

        val reload = inspector.reloadNow()
        val status = inspector.reloadStatus()
        val history = inspector.reloadHistory()

        assertEquals(GmConfigReloadRecordStatus.Success, reload.status)
        assertEquals("v2", reload.currentRevision?.version)
        assertEquals("v2", status.currentRevision?.version)
        assertEquals("v2", status.lastSuccess?.currentRevision?.version)
        assertEquals(listOf("items"), reload.changedTables.map { it.name })
        assertEquals(listOf("2"), reload.changedTables.single().keyChange?.addedKeys)
        assertEquals(listOf("1"), reload.changedTables.single().keyChange?.removedKeys)
        assertEquals(emptyList(), reload.changedTables.single().keyChange?.updatedKeys)
        assertEquals(2, history.size)
    }

    private suspend fun inspector(): SnapshotGmConfigInspector {
        val service = ConfigService(TestConfigLoader())
        service.load()
        return SnapshotGmConfigInspector(service)
    }

    private class TestConfigLoader : ConfigLoader {
        override suspend fun load(): ConfigSnapshot {
            val rows = listOf(
                ItemConfig(1, "Sword", 5, RewardConfig(1001, 1)),
                ItemConfig(2, "Potion", 1, RewardConfig(1002, 5)),
                ItemConfig(3, "Shield", 3, RewardConfig(1003, 1)),
            ).associateBy { it.id }
            return DefaultConfigSnapshot(
                revision = ConfigRevision("v1", checksum = "abc"),
                tables = listOf(mapConfigTable("items", rows)),
            )
        }
    }

    private class VersionedConfigLoader : ConfigLoader {
        private var version = 0

        override suspend fun load(): ConfigSnapshot {
            version += 1
            val rows = listOf(
                ItemConfig(version, "Sword-$version", 5, RewardConfig(1001, version)),
            ).associateBy { it.id }
            return DefaultConfigSnapshot(
                revision = ConfigRevision("v$version"),
                tables = listOf(mapConfigTable("items", rows)),
            )
        }
    }

    private data class ItemConfig(
        val id: Int,
        val name: String,
        val quality: Int,
        val reward: RewardConfig,
    )

    private data class RewardConfig(
        val itemId: Int,
        val count: Int,
    )
}
