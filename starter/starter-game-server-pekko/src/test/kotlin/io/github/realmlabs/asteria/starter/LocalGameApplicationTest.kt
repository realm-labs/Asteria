package io.github.realmlabs.asteria.starter

import io.github.realmlabs.asteria.cluster.config.ClusterConfigLayout
import io.github.realmlabs.asteria.cluster.config.RuntimeNodeConfig
import io.github.realmlabs.asteria.cluster.pekko.PekkoRuntime
import io.github.realmlabs.asteria.config.center.InMemoryConfigStore
import io.github.realmlabs.asteria.config.center.configPath
import io.github.realmlabs.asteria.core.NodeState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LocalGameApplicationTest {
    @Test
    fun localRuntimeLaunchesAndStops() = runBlocking {
        val app = localGameApplication {
            name = "asteria-test-${System.nanoTime()}"
            role("test")
        }

        app.launch()
        assertEquals(NodeState.Started, app.state)
        assertNotNull(app.services.find<PekkoRuntime>())
        val summary = app.services.get<GameServerStartupSummary>()
        assertEquals("local", summary.topologySource)
        assertEquals(setOf("test"), summary.roles)

        app.stop()
        assertEquals(NodeState.Stopped, app.state)
    }

    @Test
    fun localClusterBuildsStaticTopology() {
        val cluster = localGameCluster {
            name = "asteria-local-test"
            application {
                role("shared")
            }
            node("seed-1", "seed")
            node("player-1", "player")
        }

        assertEquals(listOf("player-1", "seed-1"), cluster.applications.keys.sorted())
        assertEquals(listOf("seed-1"), cluster.topology.seedNodes.map { it.nodeId })
        assertEquals(setOf("seed"), cluster.topology.requireNode("seed-1").roles)
        assertEquals(setOf("player"), cluster.topology.requireNode("player-1").roles)
    }

    @Test
    fun localClusterLaunchesAndStopsNodes() = runBlocking {
        val cluster = localGameCluster {
            name = "asteria-local-${System.nanoTime()}"
            node("seed-1", "seed")
            node("player-1", "player")
        }

        try {
            cluster.launch()
            assertEquals(NodeState.Started, cluster["seed-1"].state)
            assertEquals(NodeState.Started, cluster["player-1"].state)
            assertNotNull(cluster["seed-1"].services.find<PekkoRuntime>())
            assertNotNull(cluster["player-1"].services.find<PekkoRuntime>())
            assertEquals("local-static", cluster["seed-1"].services.get<GameServerStartupSummary>().topologySource)
            assertEquals(listOf("seed-1"), cluster["player-1"].services.get<GameServerStartupSummary>().seedNodes)
        } finally {
            cluster.stop()
        }
        assertEquals(NodeState.Stopped, cluster["seed-1"].state)
        assertEquals(NodeState.Stopped, cluster["player-1"].state)
    }

    @Test
    fun configCenterClusterApplicationLoadsTopologyFromStore() = runBlocking {
        val store = InMemoryConfigStore()
        val layout = ClusterConfigLayout(configPath("/game/cluster"))
        val topology = localGameCluster {
            node("seed-1", "seed")
        }.topology
        store.publishClusterTopology(topology, layout)

        val app = clusterGameApplication(
            nodeId = "seed-1",
            store = store,
            layout = layout,
        ) {
            name = "asteria-config-center-${System.nanoTime()}"
            role("seed")
        }

        try {
            app.launch()
            val runtime = app.services.get<PekkoRuntime>()
            assertEquals(
                RuntimeNodeConfig(
                    "seed-1",
                    "127.0.0.1",
                    topology.requireNode("seed-1").port,
                    setOf("seed"),
                    seed = true
                ),
                runtime.node,
            )
            assertEquals("config-center", app.services.get<GameServerStartupSummary>().topologySource)
        } finally {
            app.stop()
        }
    }

    @Test
    fun localConfigCenterClusterPublishesTopologyAndLaunchesNodes() = runBlocking {
        val cluster = localConfigCenterGameCluster {
            name = "asteria-local-config-center-${System.nanoTime()}"
            node("seed-1", "seed")
            node("player-1", "player")
        }

        assertNotNull(cluster.store)
        assertNotNull(cluster.layout)
        assertNotNull(cluster.store.get(cluster.layout.node("seed-1")))

        try {
            cluster.launch()
            assertEquals(NodeState.Started, cluster["seed-1"].state)
            assertEquals(NodeState.Started, cluster["player-1"].state)
            assertEquals(
                "local-config-center",
                cluster["seed-1"].services.get<GameServerStartupSummary>().topologySource
            )
            assertEquals(
                listOf("player-1", "seed-1"),
                cluster["player-1"].services.get<GameServerStartupSummary>().topologyNodes.sorted()
            )
        } finally {
            cluster.stop()
        }
    }
}
