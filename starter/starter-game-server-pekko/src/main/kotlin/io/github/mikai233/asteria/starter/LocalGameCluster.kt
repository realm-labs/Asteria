package io.github.mikai233.asteria.starter

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.github.mikai233.asteria.cluster.config.ClusterConfigModule
import io.github.mikai233.asteria.cluster.config.ClusterConfigLayout
import io.github.mikai233.asteria.cluster.config.ClusterTopology
import io.github.mikai233.asteria.cluster.config.RuntimeNodeConfig
import io.github.mikai233.asteria.cluster.config.StaticClusterTopologyProvider
import io.github.mikai233.asteria.cluster.pekko.PekkoRuntimeModule
import io.github.mikai233.asteria.cluster.pekko.TopologyPekkoClusterStartup
import io.github.mikai233.asteria.config.center.ConfigCenterModule
import io.github.mikai233.asteria.config.center.ConfigCodec
import io.github.mikai233.asteria.config.center.ConfigStore
import io.github.mikai233.asteria.config.center.InMemoryConfigStore
import io.github.mikai233.asteria.config.center.JacksonConfigCodec
import io.github.mikai233.asteria.core.AsteriaApplication
import io.github.mikai233.asteria.core.AsteriaApplicationBuilder
import io.github.mikai233.asteria.core.AsteriaDsl
import io.github.mikai233.asteria.core.gameApplication
import io.github.mikai233.asteria.rpc.RpcModule
import java.net.ServerSocket

/**
 * Built local multi-node cluster for tests and local development.
 *
 * The cluster owns multiple [AsteriaApplication] instances in the same JVM. [topology] is generated before launch and
 * shared by all nodes. When built through [localConfigCenterGameCluster], [store] and [layout] expose the in-memory
 * config-center topology used by the nodes.
 */
class LocalGameCluster internal constructor(
    /**
     * Static topology generated for this local cluster.
     */
    val topology: ClusterTopology,
    /**
     * Node applications keyed by `nodeId`.
     */
    val applications: Map<String, AsteriaApplication>,
    /**
     * Config store used by config-center based local clusters, or `null` for static local clusters.
     */
    val store: ConfigStore? = null,
    /**
     * Cluster topology layout used by [store], or `null` for static local clusters.
     */
    val layout: ClusterConfigLayout? = null,
) {
    /**
     * Launches every node in declaration order.
     *
     * If any node fails to launch, nodes that already started are stopped in reverse order before the original failure
     * is rethrown.
     */
    suspend fun launch(): LocalGameCluster {
        val launched = mutableListOf<AsteriaApplication>()
        try {
            for (application in applications.values) {
                application.launch()
                launched += application
            }
            return this
        } catch (failure: Throwable) {
            launched.asReversed().forEach { application ->
                runCatching { application.stop() }
            }
            throw failure
        }
    }

    /**
     * Stops every node in reverse declaration order.
     */
    suspend fun stop() {
        applications.values.toList().asReversed().forEach { application ->
            application.stop()
        }
    }

    /**
     * Returns the application for [nodeId].
     */
    operator fun get(nodeId: String): AsteriaApplication {
        return applications[nodeId] ?: error("local game cluster does not contain node $nodeId")
    }
}

/**
 * Builds a local static-topology Pekko cluster.
 *
 * Ports default to free local TCP ports. If no node is explicitly marked as seed, the first declared node becomes the
 * seed node. The same application declarations are installed on every node, while each node may add node-local modules
 * through its own [node] block.
 */
fun localGameCluster(configure: LocalGameClusterBuilder.() -> Unit): LocalGameCluster {
    return LocalGameClusterBuilder().apply(configure).build()
}

/**
 * Builds a local multi-node cluster that uses an in-memory config center topology path.
 *
 * The generated topology is written to [configStore] before node applications are built. Each node then starts through
 * [TopologyPekkoClusterStartup] without receiving a static provider directly, which makes local tests exercise the same
 * topology lookup path as config-center based deployments.
 */
suspend fun localConfigCenterGameCluster(
    configStore: ConfigStore = InMemoryConfigStore(),
    layout: ClusterConfigLayout? = null,
    configCodec: ConfigCodec = JacksonConfigCodec(),
    configure: LocalGameClusterBuilder.() -> Unit,
): LocalGameCluster {
    return LocalGameClusterBuilder().apply(configure).buildConfigCenter(configStore, layout, configCodec)
}

/**
 * Builds and immediately launches a [localGameCluster].
 */
suspend fun launchLocalGameCluster(configure: LocalGameClusterBuilder.() -> Unit): LocalGameCluster {
    return localGameCluster(configure).launch()
}

/**
 * Builds and immediately launches a [localConfigCenterGameCluster].
 */
suspend fun launchLocalConfigCenterGameCluster(
    configStore: ConfigStore = InMemoryConfigStore(),
    layout: ClusterConfigLayout? = null,
    configCodec: ConfigCodec = JacksonConfigCodec(),
    configure: LocalGameClusterBuilder.() -> Unit,
): LocalGameCluster {
    return localConfigCenterGameCluster(configStore, layout, configCodec, configure).launch()
}

@AsteriaDsl
class LocalGameClusterBuilder {
    /**
     * Application / Pekko ActorSystem name shared by all local nodes.
     */
    var name: String = "asteria-local-${System.nanoTime()}"
    /**
     * Default host for nodes that do not override [node]'s `host` parameter.
     */
    var host: String = "127.0.0.1"
    /**
     * Extra Pekko config merged into every local node.
     */
    var pekkoConfig: Config = ConfigFactory.empty()

    private val applicationConfigurators: MutableList<AsteriaApplicationBuilder.() -> Unit> = mutableListOf()
    private val nodes: MutableList<LocalGameClusterNodeBuilder> = mutableListOf()

    /**
     * Common application declarations installed on every local node.
     */
    fun application(configure: AsteriaApplicationBuilder.() -> Unit) {
        applicationConfigurators += configure
    }

    /**
     * Adds one local node.
     *
     * A `port` of `0` means the builder allocates a free local TCP port when [build] or [buildConfigCenter] runs.
     * [configure] is node-local application DSL and runs after common [application] declarations.
     */
    fun node(
        nodeId: String,
        vararg roles: String,
        seed: Boolean = false,
        host: String = this.host,
        port: Int = 0,
        attributes: Map<String, String> = emptyMap(),
        configure: AsteriaApplicationBuilder.() -> Unit = {},
    ) {
        nodes += LocalGameClusterNodeBuilder(
            nodeId = nodeId,
            roles = roles.toSet(),
            seed = seed,
            host = host,
            port = port,
            attributes = attributes,
            configure = configure,
        )
    }

    /**
     * Adds one seed node.
     *
     * This is equivalent to [node] with `seed = true`.
     */
    fun seedNode(
        nodeId: String,
        vararg roles: String,
        host: String = this.host,
        port: Int = 0,
        attributes: Map<String, String> = emptyMap(),
        configure: AsteriaApplicationBuilder.() -> Unit = {},
    ) {
        node(
            nodeId = nodeId,
            roles = roles,
            seed = true,
            host = host,
            port = port,
            attributes = attributes,
            configure = configure,
        )
    }

    /**
     * Builds a static-topology local cluster.
     */
    fun build(): LocalGameCluster {
        val runtimeNodes = runtimeNodes()
        val topology = ClusterTopology(runtimeNodes)
        val provider = StaticClusterTopologyProvider(topology)
        val applications = nodes.zip(runtimeNodes).associate { (node, runtimeNode) ->
            runtimeNode.nodeId to gameApplication app@{
                name = this@LocalGameClusterBuilder.name
                install(RpcModule.autoDiscover())
                this@LocalGameClusterBuilder.applySharedApplicationConfig(this@app, node)
                install(
                    ClusterConfigModule {
                        provider(provider)
                    },
                )
                install(
                    PekkoRuntimeModule(
                        TopologyPekkoClusterStartup(
                            nodeId = runtimeNode.nodeId,
                            topologyProvider = provider,
                            config = this@LocalGameClusterBuilder.pekkoConfig,
                        ),
                    ),
                )
                install(GameServerStartupSummaryModule("local-static"))
            }
        }
        return LocalGameCluster(topology, applications)
    }

    /**
     * Builds a config-center-topology local cluster.
     *
     * The generated topology is published to [configStore] under [layout] before any node application is built.
     */
    suspend fun buildConfigCenter(
        configStore: ConfigStore = InMemoryConfigStore(),
        layout: ClusterConfigLayout? = null,
        configCodec: ConfigCodec = JacksonConfigCodec(),
    ): LocalGameCluster {
        val runtimeNodes = runtimeNodes()
        val topology = ClusterTopology(runtimeNodes)
        val resolvedLayout = layout ?: ClusterConfigLayout.default(name)
        configStore.publishClusterTopology(topology, resolvedLayout, configCodec)
        val applications = nodes.zip(runtimeNodes).associate { (node, runtimeNode) ->
            runtimeNode.nodeId to gameApplication app@{
                name = this@LocalGameClusterBuilder.name
                install(
                    ConfigCenterModule {
                        store(configStore)
                        codec(configCodec)
                    },
                )
                install(RpcModule.autoDiscover())
                this@LocalGameClusterBuilder.applySharedApplicationConfig(this@app, node)
                install(
                    ClusterConfigModule {
                        this.layout = resolvedLayout
                    },
                )
                install(
                    PekkoRuntimeModule(
                        TopologyPekkoClusterStartup(
                            nodeId = runtimeNode.nodeId,
                            config = this@LocalGameClusterBuilder.pekkoConfig,
                        ),
                    ),
                )
                install(GameServerStartupSummaryModule("local-config-center"))
            }
        }
        return LocalGameCluster(topology, applications, configStore, resolvedLayout)
    }

    private fun runtimeNodes(): List<RuntimeNodeConfig> {
        require(nodes.isNotEmpty()) { "local game cluster must contain at least one node" }
        val hasSeed = nodes.any { it.seed }
        return nodes.mapIndexed { index, node ->
            RuntimeNodeConfig(
                nodeId = node.nodeId,
                host = node.host,
                port = node.port.takeIf { it > 0 } ?: freeTcpPort(),
                roles = node.roles,
                seed = node.seed || (!hasSeed && index == 0),
                attributes = node.attributes,
            )
        }
    }

    private fun applySharedApplicationConfig(
        app: AsteriaApplicationBuilder,
        node: LocalGameClusterNodeBuilder,
    ) {
        applicationConfigurators.forEach { configure ->
            configure(app)
        }
        node.roles.forEach { app.role(it) }
        node.configure(app)
    }
}

private data class LocalGameClusterNodeBuilder(
    val nodeId: String,
    val roles: Set<String>,
    val seed: Boolean,
    val host: String,
    val port: Int,
    val attributes: Map<String, String>,
    val configure: AsteriaApplicationBuilder.() -> Unit,
) {
    init {
        require(nodeId.isNotBlank()) { "local cluster node id must not be blank" }
        require(host.isNotBlank()) { "local cluster node host must not be blank" }
        require(port in 0..65535) { "local cluster node port must be in 0..65535" }
        require(roles.all { it.isNotBlank() }) { "local cluster node roles must not contain blank values" }
    }
}

private fun freeTcpPort(): Int {
    return ServerSocket(0).use { it.localPort }
}
