package io.github.mikai233.asteria.cluster.pekko

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.github.mikai233.asteria.cluster.config.ClusterTopology
import io.github.mikai233.asteria.cluster.config.ClusterTopologyProvider
import io.github.mikai233.asteria.cluster.config.RuntimeNodeConfig
import io.github.mikai233.asteria.core.AsteriaModule
import io.github.mikai233.asteria.core.EntitySpec
import io.github.mikai233.asteria.core.ModuleContext
import io.github.mikai233.asteria.core.RoleKey
import io.github.mikai233.asteria.core.SingletonSpec
import org.apache.pekko.actor.ActorRef
import kotlinx.coroutines.future.await
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.cluster.sharding.ShardCoordinator
import org.apache.pekko.cluster.sharding.ShardRegion
import scala.jdk.javaapi.FutureConverters

class PekkoRuntimeModule private constructor(
    private val startupFactory: suspend (ModuleContext) -> PekkoRuntimeStartup,
) : AsteriaModule {
    override val name: String = "pekko-runtime"

    private var runtime: PekkoRuntime? = null

    override suspend fun install(context: ModuleContext) {
        val startup = startupFactory(context)
        val system = ActorSystem.create(context.name, startup.config)
        val nodeRoles = startup.node?.roleKeys ?: system.configuredRoleKeys()
        context.application.setNodeRoles(nodeRoles)
        if (startup.selfJoin) {
            val cluster = Cluster.get(system)
            cluster.join(cluster.selfAddress())
        }
        val pekkoRuntime = PekkoRuntime(system, startup.node, startup.topology)
        runtime = pekkoRuntime
        context.services.register(PekkoRuntime::class, pekkoRuntime)
        context.services.register(ActorSystem::class, system)
        startup.node?.let { context.services.register(RuntimeNodeConfig::class, it) }
        startup.topology?.let { context.services.register(ClusterTopology::class, it) }
        context.services.register(EntityShardRegistry::class, EntityShardRegistry())
        context.services.register(SingletonActorRegistry::class, SingletonActorRegistry())
    }

    override suspend fun start(context: ModuleContext) {
        val system = context.services.get<ActorSystem>()
        startEntities(context, system, context.application.roles)
        startSingletons(context, system, context.application.roles)
    }

    override suspend fun stop(context: ModuleContext) {
        val system = runtime?.system ?: return
        FutureConverters.asJava(system.terminate()).await()
        runtime = null
    }

    private fun startEntities(
        context: ModuleContext,
        system: ActorSystem,
        nodeRoles: Set<RoleKey>,
    ) {
        val registry = context.services.get<EntityShardRegistry>()
        context.entities.forEach { spec ->
            val extractor = spec.extractor() ?: PekkoShardExtractors.shardMessageByEntityIdHash(spec.shardCount)
            // Entity startup is explicit: Auto follows role ownership, Region fails on role mismatch,
            // and Proxy always starts a proxy even if this node owns the role.
            val ref = when (spec.shardStartup()) {
                PekkoShardStartup.Auto -> {
                    if (spec.role == null || spec.role in nodeRoles) {
                        startEntityRegion(context, system, spec, extractor)
                    } else {
                        startEntityProxy(system, spec, extractor)
                    }
                }

                PekkoShardStartup.Region -> {
                    require(spec.role == null || spec.role in nodeRoles) {
                        "entity ${spec.kind} requires role ${spec.role}, but this node has roles $nodeRoles"
                    }
                    startEntityRegion(context, system, spec, extractor)
                }

                PekkoShardStartup.Proxy -> startEntityProxy(system, spec, extractor)
            }
            registry.register(spec.kind, ref)
        }
    }

    private fun startEntityRegion(
        context: ModuleContext,
        system: ActorSystem,
        spec: EntitySpec<*>,
        extractor: ShardRegion.MessageExtractor,
    ): ActorRef {
        val propsFactory = spec.propsFactory() ?: error("entity ${spec.kind} requires actor props to start shard region")
        val strategy = spec.allocationStrategy() ?: ShardCoordinator.LeastShardAllocationStrategy(1, 10)
        return system.startAsteriaSharding(
            spec = spec,
            props = propsFactory(context.application, spec),
            extractor = extractor,
            strategy = strategy,
        )
    }

    private fun startEntityProxy(
        system: ActorSystem,
        spec: EntitySpec<*>,
        extractor: ShardRegion.MessageExtractor,
    ): ActorRef {
        return system.startAsteriaShardingProxy(spec.kind.value, spec.role, extractor)
    }

    private fun startSingletons(
        context: ModuleContext,
        system: ActorSystem,
        nodeRoles: Set<RoleKey>,
    ) {
        val registry = context.services.get<SingletonActorRegistry>()
        context.singletons.forEach { spec ->
            // Singleton startup controls whether this node hosts the manager. The application-facing
            // ref is always a proxy, including on nodes that host the singleton manager.
            when (spec.singletonStartup()) {
                PekkoSingletonStartup.Auto -> {
                    if (spec.role in nodeRoles) {
                        startSingletonHost(context, system, spec)
                    }
                }

                PekkoSingletonStartup.Host -> {
                    require(spec.role in nodeRoles) {
                        "singleton ${spec.name} requires role ${spec.role}, but this node has roles $nodeRoles"
                    }
                    startSingletonHost(context, system, spec)
                }

                PekkoSingletonStartup.Proxy -> Unit
            }
            val ref = startSingletonProxy(system, spec)
            registry.register(spec.name, ref)
        }
    }

    private fun startSingletonHost(
        context: ModuleContext,
        system: ActorSystem,
        spec: SingletonSpec,
    ): ActorRef {
        val propsFactory = spec.propsFactory() ?: error("singleton ${spec.name} requires actor props to start host")
        return system.startAsteriaSingleton(spec, propsFactory(context.application, spec))
    }

    private fun startSingletonProxy(
        system: ActorSystem,
        spec: SingletonSpec,
    ): ActorRef {
        return system.startAsteriaSingletonProxy(spec.name.value, spec.role)
    }

    companion object {
        fun local(config: Config = ConfigFactory.empty()): PekkoRuntimeModule {
            return PekkoRuntimeModule(
                startupFactory = { context ->
                    val runtimeConfig = mapOf(
                        "pekko.actor.provider" to "cluster",
                        "pekko.remote.artery.canonical.hostname" to "127.0.0.1",
                        "pekko.remote.artery.canonical.port" to 0,
                        "pekko.cluster.roles" to context.declaredRoles.map { it.value },
                        "pekko.cluster.jmx.multi-mbeans-in-same-jvm" to "on",
                    )
                    PekkoRuntimeStartup(
                        config = ConfigFactory.parseMap(runtimeConfig)
                        .withFallback(config)
                            .withFallback(ConfigFactory.load()),
                        selfJoin = true,
                    )
                },
            )
        }

        fun fromConfig(configFactory: (ModuleContext) -> Config): PekkoRuntimeModule {
            return PekkoRuntimeModule { context ->
                PekkoRuntimeStartup(configFactory(context), selfJoin = false)
            }
        }

        fun cluster(
            nodeId: String,
            config: Config = ConfigFactory.empty(),
        ): PekkoRuntimeModule {
            return clusterFromProvider(
                nodeId = nodeId,
                topologyProvider = null,
                config = config,
            )
        }

        fun cluster(
            nodeId: String,
            topologyProvider: ClusterTopologyProvider,
            config: Config = ConfigFactory.empty(),
        ): PekkoRuntimeModule {
            return clusterFromProvider(
                nodeId = nodeId,
                topologyProvider = topologyProvider,
                config = config,
            )
        }

        private fun clusterFromProvider(
            nodeId: String,
            topologyProvider: ClusterTopologyProvider?,
            config: Config,
        ): PekkoRuntimeModule {
            require(nodeId.isNotBlank()) { "nodeId must not be blank" }
            return PekkoRuntimeModule { context ->
                val provider = topologyProvider ?: context.services.get(ClusterTopologyProvider::class)
                val topology = provider.current()
                validateDeclaredRoles(context.declaredRoles, topology)
                val node = topology.requireNode(nodeId)
                PekkoRuntimeStartup(
                    config = PekkoClusterConfig.build(context.name, node, topology, config),
                    selfJoin = false,
                    node = node,
                    topology = topology,
                )
            }
        }
    }
}

private data class PekkoRuntimeStartup(
    val config: Config,
    val selfJoin: Boolean,
    val node: RuntimeNodeConfig? = null,
    val topology: ClusterTopology? = null,
)

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

private val RuntimeNodeConfig.roleKeys: Set<RoleKey>
    get() = roles.map(::RoleKey).toSet()

private fun RuntimeNodeConfig.toPekkoAddress(systemName: String): String {
    return "pekko://$systemName@$host:$port"
}

private fun ActorSystem.configuredRoleKeys(): Set<RoleKey> {
    val config = settings().config()
    return if (config.hasPath("pekko.cluster.roles")) {
        config.getStringList("pekko.cluster.roles").map(::RoleKey).toSet()
    } else {
        emptySet()
    }
}
