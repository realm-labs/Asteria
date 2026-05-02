package io.github.mikai233.asteria.cluster.pekko

import io.github.mikai233.asteria.cluster.config.ClusterTopology
import io.github.mikai233.asteria.cluster.config.RuntimeNodeConfig
import io.github.mikai233.asteria.cluster.config.StaticClusterTopologyProvider
import io.github.mikai233.asteria.core.AsteriaModule
import io.github.mikai233.asteria.core.EntityKind
import io.github.mikai233.asteria.core.ModuleContext
import io.github.mikai233.asteria.core.RoleKey
import io.github.mikai233.asteria.core.SingletonName
import io.github.mikai233.asteria.core.gameApplication
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.apache.pekko.actor.ActorSystem
import scala.jdk.javaapi.FutureConverters
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class PekkoRuntimeModuleClusterTest {
    @Test
    fun `cluster config is generated from topology`() {
        val topology = ClusterTopology(
            listOf(
                RuntimeNodeConfig("seed-1", "127.0.0.1", 2551, setOf("seed", "player"), seed = true),
                RuntimeNodeConfig("match-1", "127.0.0.2", 2552, setOf("match")),
            ),
        )

        val config = PekkoClusterConfig.build(
            systemName = "demo-game",
            node = topology.requireNode("match-1"),
            topology = topology,
        )

        assertEquals("cluster", config.getString("pekko.actor.provider"))
        assertEquals("127.0.0.2", config.getString("pekko.remote.artery.canonical.hostname"))
        assertEquals(2552, config.getInt("pekko.remote.artery.canonical.port"))
        assertEquals(listOf("match"), config.getStringList("pekko.cluster.roles"))
        assertEquals(listOf("pekko://demo-game@127.0.0.1:2551"), config.getStringList("pekko.cluster.seed-nodes"))
    }

    @Test
    fun `cluster runtime launches from static topology`() = runBlocking {
        val port = freeTcpPort()
        val topology = ClusterTopology(
            listOf(
                RuntimeNodeConfig("seed-1", "127.0.0.1", port, setOf("seed"), seed = true),
                RuntimeNodeConfig("player-1", "127.0.0.1", freeTcpPort(), setOf("player")),
            ),
        )
        val app = gameApplication {
            name = "asteria-cluster-test-${System.nanoTime()}"
            role("player")
            install(
                PekkoRuntimeModule(
                    TopologyPekkoClusterStartup("seed-1", StaticClusterTopologyProvider(topology)),
                ),
            )
        }

        app.launch()
        val runtime = app.services.get<PekkoRuntime>()

        assertEquals("seed-1", runtime.node?.nodeId)
        assertNotNull(runtime.topology)
        assertEquals(setOf(RoleKey("seed")), app.roles)
        assertEquals(setOf(RoleKey("player")), app.declaredRoles)
        assertEquals(listOf("seed"), runtime.system.settings().config().getStringList("pekko.cluster.roles"))

        app.stop()
    }

    @Test
    fun `coordinated shutdown stops modules after pekko runtime`() = runBlocking {
        val events = mutableListOf<String>()
        val app = gameApplication {
            name = "asteria-cluster-shutdown-test-${System.nanoTime()}"
            role("player")
            install(PekkoRuntimeModule(LocalPekkoClusterStartup()))
            install(RecordingAsteriaModule("feature", events))
        }

        app.launch()
        val system = app.services.get<ActorSystem>()
        FutureConverters.asJava(system.terminate()).await()

        assertEquals(listOf("install:feature", "start:feature", "stop:feature"), events)
        app.stop()
    }

    @Test
    fun `proxy startup can force proxy on a node that owns the entity role`() = runBlocking {
        val topology = ClusterTopology(
            listOf(
                RuntimeNodeConfig("player-1", "127.0.0.1", freeTcpPort(), setOf("player"), seed = true),
            ),
        )
        val app = gameApplication {
            name = "asteria-cluster-proxy-test-${System.nanoTime()}"
            entity<Long>("player") {
                role("player")
                shardStartup(PekkoShardStartup.Proxy)
            }
            install(
                PekkoRuntimeModule(
                    TopologyPekkoClusterStartup("player-1", StaticClusterTopologyProvider(topology)),
                ),
            )
        }

        app.launch()
        val ref = app.services.get<EntityShardRegistry>()[EntityKind("player")]

        assertEquals("playerProxy", ref.path().name())

        app.stop()
    }

    @Test
    fun `proxy startup can force singleton proxy on a node that owns the singleton role`() = runBlocking {
        val topology = ClusterTopology(
            listOf(
                RuntimeNodeConfig("world-1", "127.0.0.1", freeTcpPort(), setOf("world"), seed = true),
            ),
        )
        val app = gameApplication {
            name = "asteria-cluster-singleton-proxy-test-${System.nanoTime()}"
            singleton("world") {
                role("world")
                singletonStartup(PekkoSingletonStartup.Proxy)
            }
            install(
                PekkoRuntimeModule(
                    TopologyPekkoClusterStartup("world-1", StaticClusterTopologyProvider(topology)),
                ),
            )
        }

        app.launch()
        val ref = app.services.get<SingletonActorRegistry>()[SingletonName("world")]

        assertEquals("worldProxy", ref.path().name())

        app.stop()
    }

    @Test
    fun `cluster runtime rejects topology missing declared roles`() = runBlocking {
        val topology = ClusterTopology(
            listOf(
                RuntimeNodeConfig("seed-1", "127.0.0.1", freeTcpPort(), setOf("seed"), seed = true),
            ),
        )
        val app = gameApplication {
            name = "asteria-cluster-missing-role-test-${System.nanoTime()}"
            role("player")
            install(
                PekkoRuntimeModule(
                    TopologyPekkoClusterStartup("seed-1", StaticClusterTopologyProvider(topology)),
                ),
            )
        }

        val failure = assertFailsWith<IllegalArgumentException> {
            app.launch()
        }

        assertEquals("cluster topology does not cover declared roles: player", failure.message)
    }

    private fun freeTcpPort(): Int {
        return ServerSocket(0).use { it.localPort }
    }
}

private class RecordingAsteriaModule(
    override val name: String,
    private val events: MutableList<String>,
) : AsteriaModule {
    override suspend fun install(context: ModuleContext) {
        events += "install:$name"
    }

    override suspend fun start(context: ModuleContext) {
        events += "start:$name"
    }

    override suspend fun stop(context: ModuleContext) {
        events += "stop:$name"
    }
}
