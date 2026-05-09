package io.github.realmlabs.asteria.config

import io.github.realmlabs.asteria.core.gameApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*
import kotlin.time.Duration

class ConfigServiceTest {
    @Test
    fun `reload loads typed tables builds components and notifies listeners`() = runBlocking {
        val loader = TestConfigLoader()
        val service = ConfigService(loader, componentBuilders = listOf(generatedTablesBuilder()))
        var notified: ConfigReloadResult? = null
        service.subscribe { result -> notified = result }

        val result = service.reload()
        val table = result.current[TestConfigTables.Items]

        assertNull(result.previous)
        assertEquals("v1", result.current.revision.version)
        assertEquals("Sword", table.require(1).name)
        assertEquals(2, table.size)
        assertEquals(ConfigTableName("items"), TestConfigTables.Items.name)
        assertEquals("project-configs", result.current.component<GeneratedTables>().name)
        assertEquals(result, notified)
        val diff = ConfigSnapshotDiff.between(result.previous, result.current)
        assertEquals(listOf("items"), diff.addedTables.map { it.name.value })
    }

    @Test
    fun `map config table exposes read only map api`() {
        val table = mapConfigTable(
            ref = TestConfigTables.Items,
            rows = listOf(
                ItemConfig(1, "Sword", 10),
                ItemConfig(2, "Potion", 5),
            ).associateBy { it.id },
        )

        assertEquals(setOf(1, 2), table.keys)
        assertEquals(listOf("Sword", "Potion"), table.values.map { it.name })
        assertEquals(table.values, table.all())
        assertTrue(table.containsKey(1))
        assertTrue(table.contains(2))
        assertFalse(table.isEmpty())
        assertEquals(mapOf(1 to "Sword", 2 to "Potion"), table.mapValues { it.value.name })
        assertEquals(mapOf(2 to ItemConfig(2, "Potion", 5)), table.filterKeys { it == 2 })
    }

    @Test
    fun `ordered map config table preserves supplied iteration order`() {
        val table = orderedMapConfigTable(
            ref = TestConfigTables.Items,
            rows = listOf(
                2 to ItemConfig(2, "Potion", 5),
                1 to ItemConfig(1, "Sword", 10),
            ),
        )

        assertEquals(listOf(2, 1), table.keys.toList())
        assertEquals(listOf("Potion", "Sword"), table.all().map { it.name })
        assertEquals("Sword", table.require(1).name)
    }

    @Test
    fun `list and single config tables expose unkeyed row shapes`() {
        val dropsRef = rowConfigTableRef<ItemConfig>("drops")
        val globalRef = rowConfigTableRef<ItemConfig>("global")
        val list = listConfigTable(
            ref = dropsRef,
            rows = listOf(
                ItemConfig(1, "Gold", 1),
                ItemConfig(2, "Gem", 2),
            ),
        )
        val single = singleConfigTable(
            ref = globalRef,
            row = ItemConfig(1, "Server", 0),
        )
        val snapshot = DefaultConfigSnapshot(
            revision = ConfigRevision("v1"),
            tables = listOf(list, single),
        )

        assertEquals(2, list.size)
        assertEquals(listOf("Gold", "Gem"), list.all().map { it.name })
        assertEquals("Gem", list[1].name)
        assertEquals(1, single.size)
        assertEquals("Server", single.get().name)
        assertEquals(listOf(single.row), single.all())
        assertEquals(list, snapshot.requireTable(dropsRef))
        assertEquals(single, snapshot[globalRef])
    }

    @Test
    fun `snapshot returns table by concrete table type`() {
        val table = ItemConfigTable(
            listOf(
                1 to ItemConfig(1, "Sword", 10),
                2 to ItemConfig(2, "Potion", 5),
            ),
        )
        val snapshot = DefaultConfigSnapshot(
            revision = ConfigRevision("v1"),
            tables = listOf(table),
        )

        assertSame(table, snapshot.table(ItemConfigTable::class))
        assertSame(table, snapshot.table<ItemConfigTable>())
    }

    @Test
    fun `snapshot does not treat generic table implementations as concrete table types`() {
        val table = mapConfigTable(
            ref = TestConfigTables.Items,
            rows = mapOf(1 to ItemConfig(1, "Sword", 10)),
        )
        val snapshot = DefaultConfigSnapshot(
            revision = ConfigRevision("v1"),
            tables = listOf(table),
        )

        assertNull(snapshot.table(MapConfigTable::class))
        assertSame(table, snapshot[TestConfigTables.Items])
    }

    @Test
    fun `generated refs can require concrete generic table implementations`() {
        val table = mapConfigTable(
            ref = TestConfigTables.Items,
            rows = mapOf(1 to ItemConfig(1, "Sword", 10)),
        )
        val snapshot = DefaultConfigSnapshot(
            revision = ConfigRevision("v1"),
            tables = listOf(table),
        )

        val typed: MapConfigTable<Int, ItemConfig> = snapshot.requireTable(TestConfigTables.Items, MapConfigTable::class)

        assertSame(table, typed)
        assertEquals("Sword", typed.getValue(1).name)
    }

    @Test
    fun `generated refs fail fast when concrete table implementation mismatches`() {
        val table = mapConfigTable(
            ref = TestConfigTables.Items,
            rows = mapOf(1 to ItemConfig(1, "Sword", 10)),
        )
        val snapshot = DefaultConfigSnapshot(
            revision = ConfigRevision("v1"),
            tables = listOf(table),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            val typed: OrderedMapConfigTable<Int, ItemConfig> = snapshot.requireTable(
                TestConfigTables.Items,
                OrderedMapConfigTable::class,
            )
            typed.size
        }

        assertEquals(
            "config table items table type mismatch, expected io.github.realmlabs.asteria.config.OrderedMapConfigTable, " +
                    "actual io.github.realmlabs.asteria.config.MapConfigTable",
            error.message,
        )
    }

    @Test
    fun `reload rejects component build failure before publishing`() = runBlocking {
        var version = 0
        val service = ConfigService(
            loader = ConfigLoader {
                version += 1
                changingSnapshot("v$version")
            },
            componentBuilders = listOf(
                configComponentBuilder<GeneratedTables>("generated-tables") { snapshot ->
                    if (snapshot.revision.version == "v2") {
                        error("component build failed")
                    }
                    GeneratedTables(snapshot.revision.version)
                },
            ),
        )
        service.load()

        val error = assertFailsWith<IllegalStateException> {
            service.reload()
        }

        assertEquals("component build failed", error.message)
        assertEquals("v1", service.current().revision.version)
        assertEquals("v1", service.current().component<GeneratedTables>().name)
    }

    @Test
    fun `reload exposes changed event after publishing snapshot`() = runBlocking {
        var version = 0
        lateinit var service: ConfigService
        service = ConfigService(
            loader = ConfigLoader {
                version += 1
                changingSnapshot("v$version")
            },
        )
        service.load()

        val result = service.reload()
        val event = result.changeEventOrNull()

        assertEquals("v2", service.current().revision.version)
        assertNotNull(event)
        assertEquals(ConfigRevision("v1"), event.previousRevision)
        assertEquals(ConfigRevision("v2"), event.currentRevision)
        assertEquals(event.currentRevision, event.current.revision)
        assertEquals(setOf(ConfigTableName("items")), event.changedTables)
        assertEquals(event.currentRevision, service.current().revision)
    }

    @Test
    fun `reload rejects invalid snapshot`() = runBlocking {
        val service = ConfigService(
            loader = TestConfigLoader(),
            validators = listOf(
                configValidator { snapshot ->
                    val items = snapshot.requireTable(TestConfigTables.Items)
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
    fun `parallel validation keeps error order stable`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val service = ConfigService(
            loader = TestConfigLoader(),
            validators = listOf(
                configValidator {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    fail("first")
                },
                configValidator {
                    firstStarted.await()
                    fail("second")
                    releaseFirst.complete(Unit)
                },
            ),
            validationParallelism = 2,
        )

        val error = assertFailsWith<ConfigValidationException> {
            withTimeout(Duration.parse("2s")) {
                service.reload()
            }
        }

        assertEquals(listOf("first", "second"), error.errors.map { it.message })
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
        assertEquals(listOf("items"), status.lastSuccess?.diff?.addedTables?.map { it.name.value })
        assertEquals("broken", status.lastFailure?.message)
        assertEquals(2, status.recent.size)
    }

    @Test
    fun `module registers service and loads on start`() = runBlocking {
        val app = gameApplication {
            install(
                ConfigModule {
                    loader(TestConfigLoader())
                    component(generatedTablesBuilder())
                },
            )
        }

        app.launch()

        val service = app.services.get<ConfigService>()
        val monitor = app.services.get<ConfigReloadMonitor>()
        val items = service.current().requireTable(TestConfigTables.Items)
        assertNotNull(items[1])
        assertEquals("project-configs", service.current().component<GeneratedTables>().name)
        assertEquals("v1", monitor.status(service.current()).lastSuccess?.currentRevision?.version)

        app.stop()
    }

    @Test
    fun `generated table refs fail fast on type mismatch`() = runBlocking {
        val service = ConfigService(TestConfigLoader())
        val result = service.load()
        val wrongRef = configTableRef<String, ItemConfig>("items")

        val error = assertFailsWith<IllegalArgumentException> {
            result.current.requireTable(wrongRef)
        }

        assertEquals(
            "config table items key type mismatch, expected kotlin.String, actual kotlin.Int",
            error.message,
        )
    }

    private data class ItemConfig(
        val id: Int,
        val name: String,
        val price: Int,
    )

    private object TestConfigTables {
        val Items = configTableRef<Int, ItemConfig>("items")
    }

    private class ItemConfigTable(
        rows: Iterable<Pair<Int, ItemConfig>>,
    ) : OrderedMapConfigTable<Int, ItemConfig>(
        name = TestConfigTables.Items.name,
        keyType = Int::class,
        rowType = ItemConfig::class,
        rows = rows,
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
                    ref = TestConfigTables.Items,
                    rows = listOf(
                        ItemConfig(1, "Sword", 10),
                        ItemConfig(2, "Potion", 5),
                    ).associateBy { it.id },
                ),
            ),
        )
    }

    private fun changingSnapshot(version: String): ConfigSnapshot {
        return DefaultConfigSnapshot(
            revision = ConfigRevision(version),
            tables = listOf(
                mapConfigTable(
                    ref = TestConfigTables.Items,
                    rows = listOf(
                        ItemConfig(1, "Sword-$version", 10),
                        ItemConfig(2, "Potion", 5),
                    ).associateBy { it.id },
                ),
            ),
        )
    }

    private fun generatedTablesBuilder(): ConfigComponentBuilder<GeneratedTables> {
        return configComponentBuilder(
            name = "generated-tables",
            dependencies = setOf(ConfigTableName("items")),
        ) {
            GeneratedTables("project-configs")
        }
    }

    private data class GeneratedTables(
        val name: String,
    )
}
