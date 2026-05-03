package io.github.realmlabs.asteria.cluster.pekko

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.github.realmlabs.asteria.cluster.config.ClusterTopology
import io.github.realmlabs.asteria.cluster.config.ClusterTopologyProvider
import io.github.realmlabs.asteria.cluster.config.RuntimeNodeConfig
import io.github.realmlabs.asteria.core.ModuleContext
import io.github.realmlabs.asteria.core.RoleKey
import org.apache.pekko.actor.ActorSystem

/**
 * Owns how a Pekko ActorSystem is configured and how it joins a cluster.
 *
 * Runtime modules keep shard/singleton startup separate from cluster discovery: this strategy only
 * decides node roles, Pekko config, and the cluster join mechanism.
 */
interface PekkoClusterStartup {
    /**
     * Builds the startup plan before the ActorSystem is created.
     *
     * Use this phase to read topology/config services and return the final Pekko config and node
     * roles. Do not start actors here because the ActorSystem does not exist yet.
     */
    suspend fun resolve(context: ModuleContext): PekkoClusterStartPlan

    /**
     * Optional hook after the ActorSystem exists but before application entities/singletons start.
     *
     * Discovery strategies use this hook to start Pekko Management or other join mechanisms.
     */
    suspend fun afterActorSystemCreated(
        context: ModuleContext,
        system: ActorSystem,
        plan: PekkoClusterStartPlan,
    ) = Unit
}

/**
 * Complete plan used by [PekkoRuntimeModule] to create and join a Pekko cluster node.
 */
data class PekkoClusterStartPlan(
    /**
     * Final ActorSystem config.
     */
    val config: Config,
    /**
     * Roles owned by the current node.
     */
    val roles: Set<RoleKey>,
    /**
     * How the node joins the cluster after ActorSystem creation.
     */
    val join: PekkoClusterJoin,
    /**
     * Optional concrete node config when startup is topology based.
     */
    val node: RuntimeNodeConfig? = null,
    /**
     * Optional full topology when startup is topology based.
     */
    val topology: ClusterTopology? = null,
)

/**
 * Join behavior requested by a [PekkoClusterStartup].
 */
sealed interface PekkoClusterJoin {
    /**
     * Join the node to its own address. Intended for local single-node development.
     */
    data object Self : PekkoClusterJoin

    /**
     * Seed nodes are already encoded into Pekko config. Pekko performs the actual join.
     */
    data object SeedNodes : PekkoClusterJoin

    /**
     * Reserved for Pekko Management / Cluster Bootstrap based discovery startup.
     */
    data object Bootstrap : PekkoClusterJoin

    /**
     * The application or another module owns the join lifecycle.
     */
    data object External : PekkoClusterJoin
}

/**
 * Local development startup.
 *
 * The node uses declared application roles, binds Artery to `127.0.0.1:0`, and self-joins.
 */
class LocalPekkoClusterStartup(
    private val config: Config = ConfigFactory.empty(),
) : PekkoClusterStartup {
    override suspend fun resolve(context: ModuleContext): PekkoClusterStartPlan {
        val roles = context.declaredRoles
        return PekkoClusterStartPlan(
            config = localRuntimeConfig(roles)
                .withFallback(config)
                .withFallback(ConfigFactory.load()),
            roles = roles,
            join = PekkoClusterJoin.Self,
        )
    }
}

/**
 * Startup based on an Asteria [ClusterTopology].
 *
 * The current process is selected by [nodeId]. Asteria generates Pekko host, port, roles, and
 * seed-node config from the selected [RuntimeNodeConfig].
 */
class TopologyPekkoClusterStartup(
    private val nodeId: String,
    private val topologyProvider: ClusterTopologyProvider? = null,
    private val config: Config = ConfigFactory.empty(),
) : PekkoClusterStartup {
    init {
        require(nodeId.isNotBlank()) { "nodeId must not be blank" }
    }

    override suspend fun resolve(context: ModuleContext): PekkoClusterStartPlan {
        val provider = topologyProvider ?: context.services.get(ClusterTopologyProvider::class)
        val topology = provider.current()
        validateDeclaredRoles(context.declaredRoles, topology)
        val node = topology.requireNode(nodeId)
        return PekkoClusterStartPlan(
            config = PekkoClusterConfig.build(context.name, node, topology, config),
            roles = node.roleKeys,
            join = PekkoClusterJoin.SeedNodes,
            node = node,
            topology = topology,
        )
    }
}

/**
 * Escape hatch for applications that already own Pekko config or cluster join lifecycle.
 *
 * Use this when integrating with deployment-specific discovery that is not provided by an Asteria
 * adapter module.
 */
class ConfiguredPekkoClusterStartup(
    private val configFactory: suspend (ModuleContext) -> Config,
    private val rolesFactory: suspend (ModuleContext, Config) -> Set<RoleKey> = { _, config -> config.configuredRoleKeys() },
    private val join: PekkoClusterJoin = PekkoClusterJoin.External,
) : PekkoClusterStartup {
    override suspend fun resolve(context: ModuleContext): PekkoClusterStartPlan {
        val config = configFactory(context).withFallback(ConfigFactory.load())
        return PekkoClusterStartPlan(
            config = config,
            roles = rolesFactory(context, config),
            join = join,
        )
    }
}

private fun validateDeclaredRoles(
    declaredRoles: Set<RoleKey>,
    topology: ClusterTopology,
) {
    val topologyRoles = topology.nodes.flatMapTo(linkedSetOf()) { node -> node.roles.map(::RoleKey) }
    val missingRoles = declaredRoles - topologyRoles
    require(missingRoles.isEmpty()) {
        "cluster topology does not cover declared roles: ${missingRoles.joinToString()}"
    }
}

internal fun localRuntimeConfig(roles: Set<RoleKey>): Config {
    return ConfigFactory.parseMap(
        mapOf(
            "pekko.actor.provider" to "cluster",
            "pekko.remote.artery.canonical.hostname" to "127.0.0.1",
            "pekko.remote.artery.canonical.port" to 0,
            "pekko.cluster.roles" to roles.map { it.value },
            "pekko.cluster.jmx.multi-mbeans-in-same-jvm" to "on",
        ),
    )
}

internal object PekkoClusterConfig {
    fun build(
        systemName: String,
        node: RuntimeNodeConfig,
        topology: ClusterTopology,
        fallback: Config = ConfigFactory.empty(),
    ): Config {
        require(topology.nodes.any { it.nodeId == node.nodeId }) {
            "cluster topology does not contain node ${node.nodeId}"
        }
        val seedNodes = topology.seedNodes
        require(seedNodes.isNotEmpty()) { "cluster topology must contain at least one seed node" }
        val runtimeConfig = mapOf(
            "pekko.actor.provider" to "cluster",
            "pekko.remote.artery.canonical.hostname" to node.host,
            "pekko.remote.artery.canonical.port" to node.port,
            "pekko.cluster.roles" to node.roles.sorted(),
            "pekko.cluster.seed-nodes" to seedNodes.map { it.toPekkoAddress(systemName) },
            "pekko.cluster.jmx.multi-mbeans-in-same-jvm" to "on",
        )
        return ConfigFactory.parseMap(runtimeConfig)
            .withFallback(fallback)
            .withFallback(ConfigFactory.load())
    }
}

internal val RuntimeNodeConfig.roleKeys: Set<RoleKey>
    get() = roles.map(::RoleKey).toSet()

private fun RuntimeNodeConfig.toPekkoAddress(systemName: String): String {
    return "pekko://$systemName@$host:$port"
}

private fun Config.configuredRoleKeys(): Set<RoleKey> {
    return if (hasPath("pekko.cluster.roles")) {
        getStringList("pekko.cluster.roles").map(::RoleKey).toSet()
    } else {
        emptySet()
    }
}
