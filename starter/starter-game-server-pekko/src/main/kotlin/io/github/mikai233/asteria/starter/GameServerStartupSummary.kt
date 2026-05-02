package io.github.mikai233.asteria.starter

import io.github.mikai233.asteria.cluster.config.ClusterTopology
import io.github.mikai233.asteria.cluster.config.RuntimeNodeConfig
import io.github.mikai233.asteria.core.AsteriaModule
import io.github.mikai233.asteria.core.ModuleContext
import io.github.mikai233.asteria.core.RoleKey

/**
 * Startup diagnostics for a concrete game server node.
 *
 * The starter registers this as a service after the runtime module has resolved node roles and topology. It is intended
 * for tests, local multi-node tooling, and GM/debug endpoints that need a compact view of how the node was started.
 */
data class GameServerStartupSummary(
    /**
     * Application / ActorSystem name.
     */
    val applicationName: String,
    /**
     * Startup path that produced this node, such as `local`, `local-static`, `local-config-center`, or `config-center`.
     */
    val topologySource: String,
    /**
     * Runtime node id when topology based startup is used.
     */
    val nodeId: String?,
    /**
     * Published canonical host for topology based startup.
     */
    val host: String?,
    /**
     * Published canonical port for topology based startup.
     */
    val port: Int?,
    /**
     * Roles owned by this concrete running node.
     */
    val roles: Set<String>,
    /**
     * Node ids marked as seed nodes in the resolved topology.
     */
    val seedNodes: List<String>,
    /**
     * All node ids in the resolved topology.
     */
    val topologyNodes: List<String>,
    /**
     * Entity kinds declared by the application.
     */
    val entities: List<String>,
    /**
     * Singleton names declared by the application.
     */
    val singletons: List<String>,
) {
    /**
     * Renders a compact line-oriented summary suitable for logs or debug HTTP responses.
     */
    fun render(): String {
        return buildString {
            appendLine("application=$applicationName")
            appendLine("topologySource=$topologySource")
            appendLine("nodeId=${nodeId ?: "<local>"}")
            appendLine("address=${if (host != null && port != null) "$host:$port" else "<local>"}")
            appendLine("roles=${roles.sorted().joinToString(",")}")
            appendLine("seedNodes=${seedNodes.joinToString(",")}")
            appendLine("topologyNodes=${topologyNodes.joinToString(",")}")
            appendLine("entities=${entities.joinToString(",")}")
            appendLine("singletons=${singletons.joinToString(",")}")
        }.trimEnd()
    }
}

/**
 * Registers [GameServerStartupSummary] after earlier runtime modules have resolved topology and node roles.
 */
class GameServerStartupSummaryModule(
    private val topologySource: String,
) : AsteriaModule {
    override val name: String = "game-server-startup-summary"

    override suspend fun install(context: ModuleContext) {
        val node = context.services.find<RuntimeNodeConfig>()
        val topology = context.services.find<ClusterTopology>()
        context.services.register(
            GameServerStartupSummary::class,
            GameServerStartupSummary(
                applicationName = context.name,
                topologySource = topologySource,
                nodeId = node?.nodeId,
                host = node?.host,
                port = node?.port,
                roles = context.roles.mapTo(linkedSetOf(), RoleKey::value),
                seedNodes = topology?.seedNodes.orEmpty().map { it.nodeId },
                topologyNodes = topology?.nodes.orEmpty().map { it.nodeId },
                entities = context.entities.map { it.kind.value }.sorted(),
                singletons = context.singletons.map { it.name.value }.sorted(),
            ),
        )
    }
}
