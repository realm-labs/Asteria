package io.github.mikai233.asteria.cluster.config

import com.typesafe.config.ConfigFactory
import io.github.mikai233.asteria.config.center.ConfigCenterModule
import io.github.mikai233.asteria.config.center.ConfigCodec
import io.github.mikai233.asteria.config.center.InMemoryConfigStore
import io.github.mikai233.asteria.config.center.RuntimeConfigRepository
import io.github.mikai233.asteria.config.center.configPath
import io.github.mikai233.asteria.core.gameApplication
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ClusterConfigTest {
    @Test
    fun `config center topology provider resolves current topology`() = runBlocking {
        val store = InMemoryConfigStore()
        val repository = RuntimeConfigRepository(store, NodeCodec)
        val layout = ClusterConfigLayout(configPath("/asteria/cluster"))
        repository.put(layout.node("seed-1"), RuntimeNodeConfig("seed-1", "127.0.0.1", 2551, setOf("seed"), seed = true))
        repository.put(layout.node("player-1"), RuntimeNodeConfig("player-1", "127.0.0.2", 2552, setOf("player")))

        val provider = ConfigCenterClusterTopologyProvider(repository, layout)
        val topology = provider.current()

        assertEquals(2, topology.nodes.size)
        assertEquals(listOf("seed-1"), topology.seedNodes.map { it.nodeId })
        assertEquals(listOf("player-1"), topology.nodesByRole("player").map { it.nodeId })
    }

    @Test
    fun `topology provider emits updates`() = runBlocking {
        val store = InMemoryConfigStore()
        val repository = RuntimeConfigRepository(store, NodeCodec)
        val layout = ClusterConfigLayout(configPath("/asteria/cluster"))
        val provider = ConfigCenterClusterTopologyProvider(repository, layout)

        val update = async(start = CoroutineStart.UNDISPATCHED) {
            provider.watch().first { topology -> topology.nodes.any { it.nodeId == "match-1" } }
        }
        repository.put(layout.node("match-1"), RuntimeNodeConfig("match-1", "127.0.0.3", 2553, setOf("match")))

        assertEquals("match-1", update.await().nodes.single().nodeId)
    }

    @Test
    fun `module registers topology provider from repository`() = runBlocking {
        val store = InMemoryConfigStore()
        val layout = ClusterConfigLayout(configPath("/asteria/cluster"))
        val app = gameApplication {
            install(
                ConfigCenterModule {
                    store(store)
                    codec(NodeCodec)
                },
            )
            install(
                ClusterConfigModule {
                    this.layout = layout
                },
            )
        }

        app.launch()

        assertNotNull(app.services.get<ClusterTopologyProvider>())

        app.stop()
    }

    @Test
    fun `typesafe provider resolves static local topology`() = runBlocking {
        val config = ConfigFactory.parseString(
            """
            asteria.cluster.nodes = [
              {
                node-id = "seed-1"
                host = "127.0.0.1"
                port = 2551
                roles = ["seed", "player"]
                seed = true
                attributes.zone = "local-a"
              },
              {
                node-id = "match-1"
                host = "127.0.0.2"
                port = 2552
                roles = ["match"]
              }
            ]
            """.trimIndent(),
        )

        val topology = TypesafeClusterTopologyProvider(config).current()

        assertEquals(listOf("match-1", "seed-1"), topology.nodes.map { it.nodeId })
        assertEquals(listOf("seed-1"), topology.seedNodes.map { it.nodeId })
        assertEquals(setOf("seed", "player"), topology.requireNode("seed-1").roles)
        assertEquals("local-a", topology.requireNode("seed-1").attributes["zone"])
    }

    object NodeCodec : ConfigCodec {
        override fun <T : Any> decode(bytes: ByteArray, type: KClass<T>): T {
            require(type == RuntimeNodeConfig::class) { "unsupported type $type" }
            val parts = bytes.decodeToString().split("|")
            @Suppress("UNCHECKED_CAST")
            return RuntimeNodeConfig(
                nodeId = parts[0],
                host = parts[1],
                port = parts[2].toInt(),
                roles = parts[3].split(",").filter { it.isNotBlank() }.toSet(),
                seed = parts[4].toBoolean(),
            ) as T
        }

        override fun <T : Any> encode(value: T, type: KClass<T>): ByteArray {
            require(type == RuntimeNodeConfig::class) { "unsupported type $type" }
            val node = value as RuntimeNodeConfig
            return listOf(
                node.nodeId,
                node.host,
                node.port.toString(),
                node.roles.joinToString(","),
                node.seed.toString(),
            ).joinToString("|").encodeToByteArray()
        }
    }
}
