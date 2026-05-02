package io.github.mikai233.asteria.config

import io.github.mikai233.asteria.core.gameApplication
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        assertEquals(listOf("items"), result.diff.addedTables.map { it.name.value })
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
    fun `hot reload reloads after trigger signal`() = runBlocking {
        val signals = MutableSharedFlow<ConfigReloadSignal>(replay = 1)
        var version = 0
        val service = ConfigService(
            loader = ConfigLoader {
                version += 1
                snapshot("v$version")
            },
        )
        val reloaded = CompletableDeferred<ConfigReloadResult>()
        service.load()
        service.subscribe { result ->
            if (result.previous != null) {
                reloaded.complete(result)
            }
        }
        val hotReload = ConfigHotReloadService(
            service,
            ConfigHotReloadOptions(
                trigger = ConfigReloadTrigger { signals },
                debounce = Duration.ZERO,
                retryDelay = Duration.ZERO,
            ),
        )

        try {
            hotReload.start()
            signals.emit(ConfigReloadSignal("test"))

            val result = withTimeout(1_000) { reloaded.await() }

            assertEquals("v1", result.previous?.revision?.version)
            assertEquals("v2", result.current.revision.version)
            assertEquals("v2", service.current().revision.version)
        } finally {
            hotReload.stop()
        }
    }

    @Test
    fun `hot reload failure keeps previous snapshot`() = runBlocking {
        val signals = MutableSharedFlow<ConfigReloadSignal>(replay = 1)
        var attempts = 0
        val service = ConfigService(
            loader = ConfigLoader {
                attempts += 1
                if (attempts > 1) {
                    error("broken config")
                }
                snapshot("v1")
            },
        )
        service.load()
        val failed = CompletableDeferred<ConfigReloadFailed>()
        val hotReload = ConfigHotReloadService(
            service,
            ConfigHotReloadOptions(
                trigger = ConfigReloadTrigger { signals },
                debounce = Duration.ZERO,
                retryDelay = Duration.ZERO,
                failureListeners = listOf(ConfigReloadFailureListener { failed.complete(it) }),
            ),
        )

        try {
            hotReload.start()
            signals.emit(ConfigReloadSignal("test"))

            val failure = withTimeout(1_000) { failed.await() }

            assertEquals("test", failure.signal?.reason)
            assertEquals("broken config", failure.error.message)
            assertEquals("v1", service.current().revision.version)
        } finally {
            hotReload.stop()
        }
    }

    @Test
    fun `snapshot diff reports added removed and changed tables`() {
        val previous = DefaultConfigSnapshot(
            revision = ConfigRevision("v1"),
            tables = listOf(
                mapConfigTable("keep", mapOf(1 to ItemConfig(1, "Sword", 10))),
                mapConfigTable("removed", mapOf(1 to ItemConfig(1, "Old", 1))),
            ),
        )
        val current = DefaultConfigSnapshot(
            revision = ConfigRevision("v2"),
            tables = listOf(
                mapConfigTable("keep", mapOf(1 to ItemConfig(1, "Sword+", 10))),
                mapConfigTable("added", mapOf(1 to ItemConfig(1, "New", 1))),
            ),
        )

        val diff = ConfigSnapshotDiff.between(previous, current)

        assertEquals(listOf("added"), diff.addedTables.map { it.name.value })
        assertEquals(listOf("removed"), diff.removedTables.map { it.name.value })
        assertEquals(listOf("keep"), diff.changedTables.map { it.name.value })
    }

    @Test
    fun `reload monitor records success and failure`() = runBlocking {
        val monitor = ConfigReloadMonitor()
        val service = ConfigService(TestConfigLoader())
        service.subscribe(monitor)

        val result = service.load()
        monitor.failed(ConfigReloadFailed(ConfigReloadSignal("test"), IllegalStateException("broken")))

        val status = monitor.status(service.current())

        assertEquals(result.current.revision, status.currentRevision)
        assertEquals(result.current.revision, status.lastSuccess?.currentRevision)
        assertEquals("broken", status.lastFailure?.message)
        assertEquals(2, status.recent.size)
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
        val monitor = app.services.get<ConfigReloadMonitor>()
        val items = service.current().requireTable<Int, ItemConfig>(ConfigTableName("items"))
        assertNotNull(items[1])
        assertEquals("v1", monitor.status(service.current()).lastSuccess?.currentRevision?.version)

        app.stop()
    }

    private data class ItemConfig(
        val id: Int,
        val name: String,
        val price: Int,
    )

    private inner class TestConfigLoader : ConfigLoader {
        override suspend fun load(): ConfigSnapshot {
            return snapshot("v1")
        }
    }

    private fun snapshot(version: String): ConfigSnapshot {
        return DefaultConfigSnapshot(
            revision = ConfigRevision(version),
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

    private data class GeneratedTables(
        val name: String,
    )
}
