package io.github.realmlabs.asteria.config.center

import io.github.realmlabs.asteria.core.gameApplication
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KClass
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class ConfigCenterTest {
    @Test
    fun `path normalizes and joins children`() {
        val root = configPath("/asteria//cluster/")

        assertEquals("/asteria/cluster", root.value)
        assertEquals("/asteria/cluster/nodes/player-1", (root / "nodes" / "player-1").value)
        assertEquals("player-1", (root / "nodes" / "player-1").name)
    }

    @Test
    fun `in memory store supports get children watch and revision check`() = runBlocking {
        val store = InMemoryConfigStore()
        val root = configPath("/asteria/nodes")
        val child = root / "player-1"
        val watch = store.watch(root, ConfigWatchMode.Children)
        val event = async(start = CoroutineStart.UNDISPATCHED) {
            watch.events.first()
        }

        val revision = store.put(child, "one".encodeToByteArray())

        assertEquals("one", store.get(child)?.bytes?.decodeToString())
        assertEquals(listOf(child), store.children(root).map { it.path })
        assertIs<ConfigEvent.Upserted>(event.await())

        assertFailsWith<ConfigRevisionMismatchException> {
            store.put(child, "two".encodeToByteArray(), ConfigRevision("missing"))
        }

        store.put(child, "two".encodeToByteArray(), revision)
        assertEquals("two", store.get(child)?.bytes?.decodeToString())
        watch.close()
    }

    @Test
    fun `repository decodes values and emits child snapshots`() = runBlocking {
        val store = InMemoryConfigStore()
        val repository = RuntimeConfigRepository(store, TestCodec)
        val root = configPath("/settings")
        repository.put(root / "gateway", TestConfig("gate"))

        val initial = repository.children<TestConfig>(root)
        assertEquals("gate", initial.values.getValue("gateway").value.value)

        val snapshots = async(start = CoroutineStart.UNDISPATCHED) {
            repository.watchChildren<TestConfig>(root).first { snapshot ->
                "player" in snapshot.values
            }
        }
        repository.put(root / "player", TestConfig("player"))

        assertEquals("player", snapshots.await().values.getValue("player").value.value)
    }

    @Test
    fun `repository child watch resyncs after watch failure`() = runBlocking {
        val root = configPath("/settings")
        val store = FailingOnceWatchStore(root)
        val repository = RuntimeConfigRepository(store, TestCodec, watchRetryDelay = 10.milliseconds)

        val snapshot = repository.watchChildren<TestConfig>(root)
            .drop(1)
            .first { "gateway" in it.values }

        assertEquals("gate", snapshot.values.getValue("gateway").value.value)
    }

    @Test
    fun `config center reload trigger emits store changes`() = runBlocking {
        val store = InMemoryConfigStore()
        val root = configPath("/settings")
        val trigger = ConfigCenterReloadTrigger(store, root, ConfigWatchMode.Children)
        val signal = async(start = CoroutineStart.UNDISPATCHED) {
            trigger.events().first()
        }

        store.put(root / "gateway", "changed".encodeToByteArray())

        val event = signal.await()
        assertEquals("config_center_upserted", event.reason)
        assertEquals("/settings/gateway", event.source)
    }

    @Test
    fun `module registers store codec and repository`() = runBlocking {
        val app = gameApplication {
            install(
                ConfigCenterModule {
                    store(InMemoryConfigStore())
                },
            )
        }

        app.launch()

        assertNotNull(app.services.get<ConfigStore>())
        assertNotNull(app.services.get<ConfigCodec>())
        assertNotNull(app.services.get<RuntimeConfigRepository>())

        app.stop()
    }

    @Test
    fun `jackson codec encodes and decodes config`() {
        val codec = JacksonConfigCodec()
        val bytes = codec.encode(TestConfig("jackson"))

        assertEquals(TestConfig("jackson"), codec.decode<TestConfig>(bytes))
    }

    data class TestConfig(
        val value: String,
    )

    object TestCodec : ConfigCodec {
        override fun <T : Any> decode(bytes: ByteArray, type: KClass<T>): T {
            require(type == TestConfig::class) { "unsupported type $type" }
            @Suppress("UNCHECKED_CAST")
            return TestConfig(bytes.decodeToString()) as T
        }

        override fun <T : Any> encode(value: T, type: KClass<T>): ByteArray {
            require(type == TestConfig::class) { "unsupported type $type" }
            return (value as TestConfig).value.encodeToByteArray()
        }
    }

    private class FailingOnceWatchStore(
        private val root: ConfigPath,
    ) : ConfigStore {
        private var watches: Int = 0

        override suspend fun get(path: ConfigPath): ConfigEntry? {
            return entry().takeIf { it.path == path }
        }

        override suspend fun children(path: ConfigPath): List<ConfigEntry> {
            return if (path == root && watches > 1) listOf(entry()) else emptyList()
        }

        override fun watch(path: ConfigPath, mode: ConfigWatchMode): ConfigWatch {
            watches++
            val events: Flow<ConfigEvent> = if (watches == 1) {
                flow { throw IllegalStateException("watch unavailable") }
            } else {
                MutableSharedFlow()
            }
            return object : ConfigWatch {
                override val events: Flow<ConfigEvent> = events

                override fun close() = Unit
            }
        }

        override suspend fun put(
            path: ConfigPath,
            bytes: ByteArray,
            expectedRevision: ConfigRevision?
        ): ConfigRevision {
            error("not supported")
        }

        override suspend fun delete(path: ConfigPath, expectedRevision: ConfigRevision?) {
            error("not supported")
        }

        private fun entry(): ConfigEntry {
            return ConfigEntry(root / "gateway", "gate".encodeToByteArray(), ConfigRevision("1"))
        }
    }
}
