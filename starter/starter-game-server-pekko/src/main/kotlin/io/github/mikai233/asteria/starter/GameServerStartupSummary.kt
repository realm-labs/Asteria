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
    val applicationName: String,
    val topologySource: String,
    val nodeId: String?,
    val host: String?,
    val port: Int?,
    val roles: Set<String>,
    val seedNodes: List<String>,
    val topologyNodes: List<String>,
    val entities: List<String>,
    val singletons: List<String>,
) {
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
                roles = context.application.roles.mapTo(linkedSetOf(), RoleKey::value),
                seedNodes = topology?.seedNodes.orEmpty().map { it.nodeId },
                topologyNodes = topology?.nodes.orEmpty().map { it.nodeId },
                entities = context.entities.map { it.kind.value }.sorted(),
                singletons = context.singletons.map { it.name.value }.sorted(),
            ),
        )
    }
}
