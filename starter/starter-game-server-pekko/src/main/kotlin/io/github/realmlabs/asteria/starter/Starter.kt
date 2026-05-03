package io.github.realmlabs.asteria.starter

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.github.realmlabs.asteria.cluster.config.ClusterConfigLayout
import io.github.realmlabs.asteria.cluster.config.ClusterConfigModule
import io.github.realmlabs.asteria.cluster.config.ClusterTopology
import io.github.realmlabs.asteria.cluster.config.StaticClusterTopologyProvider
import io.github.realmlabs.asteria.cluster.pekko.LocalPekkoClusterStartup
import io.github.realmlabs.asteria.cluster.pekko.PekkoRuntimeModule
import io.github.realmlabs.asteria.cluster.pekko.TopologyPekkoClusterStartup
import io.github.realmlabs.asteria.config.center.*
import io.github.realmlabs.asteria.core.*
import io.github.realmlabs.asteria.message.RouteRegistry
import io.github.realmlabs.asteria.message.RouteRegistryBuilder

/**
 * Installs a prebuilt gateway/client protocol route registry.
 *
 * Most applications should use [routes] instead of constructing this module directly.
 */
class RouteModule(
    private val registry: RouteRegistry,
) : AsteriaModule {
    override val name: String = "message-routes"

    override suspend fun install(context: ModuleContext) {
        context.services.register(RouteRegistry::class, registry)
    }
}

/**
 * Installs message routes into the application.
 *
 * Routes are intentionally separate from protobuf parsing and gateway packet framing. They only describe the runtime
 * target selected for an already decoded message.
 */
fun AsteriaApplicationBuilder.routes(configure: RouteRegistryBuilder.() -> Unit) {
    install(RouteModule(RouteRegistryBuilder().apply(configure).build()))
}

/**
 * Builds a single local Pekko game node.
 *
 * The node self-joins a local cluster, uses the roles declared by the application, and registers
 * [GameServerStartupSummary]. Use this for simple local development. Use [localGameCluster] when behavior depends on
 * multiple concrete nodes.
 */
fun localGameApplication(configure: AsteriaApplicationBuilder.() -> Unit): AsteriaApplication {
    return gameApplication {
        install(PekkoRuntimeModule(LocalPekkoClusterStartup()))
        configure()
        install(GameServerStartupSummaryModule("local"))
    }
}

/**
 * Builds a cluster node whose topology is read from an already installed [RuntimeConfigRepository].
 *
 * This overload is useful when the application installs a concrete config-center module itself, for example Nacos,
 * Zookeeper, or Etcd. The node is selected by [nodeId].
 */
fun clusterGameApplication(
    nodeId: String,
    layout: ClusterConfigLayout? = null,
    pekkoConfig: Config = ConfigFactory.empty(),
    configure: AsteriaApplicationBuilder.() -> Unit,
): AsteriaApplication {
    return gameApplication {
        configure()
        val applicationName = name
        install(
            ClusterConfigModule {
                this.layout = layout ?: ClusterConfigLayout.default(applicationName)
            },
        )
        install(PekkoRuntimeModule(TopologyPekkoClusterStartup(nodeId, config = pekkoConfig)))
        install(GameServerStartupSummaryModule("config-center"))
    }
}

/**
 * Builds a cluster node and installs a [ConfigCenterModule] backed by [store].
 *
 * This is the compact startup path for tests, tools, or deployments that already have a concrete [ConfigStore]
 * instance. The topology is loaded from [layout], defaulting to the application name.
 */
fun clusterGameApplication(
    nodeId: String,
    store: ConfigStore,
    layout: ClusterConfigLayout? = null,
    codec: ConfigCodec = JacksonConfigCodec(),
    pekkoConfig: Config = ConfigFactory.empty(),
    configure: AsteriaApplicationBuilder.() -> Unit,
): AsteriaApplication {
    return gameApplication {
        install(
            ConfigCenterModule {
                store(store)
                codec(codec)
            },
        )
        configure()
        val applicationName = name
        install(
            ClusterConfigModule {
                this.layout = layout ?: ClusterConfigLayout.default(applicationName)
            },
        )
        install(PekkoRuntimeModule(TopologyPekkoClusterStartup(nodeId, config = pekkoConfig)))
        install(GameServerStartupSummaryModule("config-center"))
    }
}

/**
 * Builds a cluster node from an in-memory/static [topology].
 *
 * This path does not require a config center. It is useful for tests and local fixed-topology runs. For local tests that
 * should exercise the same config-center topology path as production, use [localConfigCenterGameCluster].
 */
fun clusterGameApplication(
    nodeId: String,
    topology: ClusterTopology,
    pekkoConfig: Config = ConfigFactory.empty(),
    configure: AsteriaApplicationBuilder.() -> Unit,
): AsteriaApplication {
    return gameApplication {
        configure()
        install(
            ClusterConfigModule {
                provider(StaticClusterTopologyProvider(topology))
            },
        )
        install(
            PekkoRuntimeModule(
                TopologyPekkoClusterStartup(
                    nodeId = nodeId,
                    topologyProvider = StaticClusterTopologyProvider(topology),
                    config = pekkoConfig,
                ),
            ),
        )
        install(GameServerStartupSummaryModule("static"))
    }
}

/**
 * Writes a full cluster topology into a config store using [ClusterConfigLayout.node] paths.
 *
 * This helper is intentionally small: it only publishes node records. It does not remove nodes that are no longer in
 * [topology], so tools that manage topology replacement should handle cleanup explicitly.
 */
suspend fun ConfigStore.publishClusterTopology(
    topology: ClusterTopology,
    layout: ClusterConfigLayout,
    codec: ConfigCodec = JacksonConfigCodec(),
) {
    val repository = RuntimeConfigRepository(this, codec)
    for (node in topology.nodes) {
        repository.put(layout.node(node.nodeId), node)
    }
}
