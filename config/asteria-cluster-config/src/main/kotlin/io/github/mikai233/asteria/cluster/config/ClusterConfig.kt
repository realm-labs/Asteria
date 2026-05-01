package io.github.mikai233.asteria.cluster.config

import com.typesafe.config.Config
import io.github.mikai233.asteria.config.center.ConfigPath
import io.github.mikai233.asteria.config.center.RuntimeConfigRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

data class RuntimeNodeConfig(
    val nodeId: String,
    val host: String,
    val port: Int,
    val roles: Set<String>,
    val seed: Boolean = false,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(nodeId.isNotBlank()) { "node id must not be blank" }
        require(host.isNotBlank()) { "node host must not be blank" }
        require(port in 1..65535) { "node port must be in 1..65535" }
        require(roles.all { it.isNotBlank() }) { "node roles must not contain blank values" }
    }
}

data class ClusterTopology(
    val nodes: List<RuntimeNodeConfig>,
) {
    init {
        val duplicateNodeIds = nodes.groupBy { it.nodeId }.filterValues { it.size > 1 }.keys
        require(duplicateNodeIds.isEmpty()) { "cluster topology contains duplicate node ids: $duplicateNodeIds" }
        val duplicateAddresses = nodes.groupBy { "${it.host}:${it.port}" }.filterValues { it.size > 1 }.keys
        require(duplicateAddresses.isEmpty()) { "cluster topology contains duplicate node addresses: $duplicateAddresses" }
    }

    val seedNodes: List<RuntimeNodeConfig> get() = nodes.filter { it.seed }

    fun nodesByRole(role: String): List<RuntimeNodeConfig> {
        return nodes.filter { role in it.roles }
    }

    fun requireNode(nodeId: String): RuntimeNodeConfig {
        return nodes.firstOrNull { it.nodeId == nodeId }
            ?: error("cluster topology does not contain node $nodeId")
    }
}

data class ClusterConfigLayout(
    val root: ConfigPath,
) {
    val nodes: ConfigPath = root / "nodes"

    fun node(nodeId: String): ConfigPath {
        return nodes / nodeId
    }

    companion object {
        fun default(namespace: String): ClusterConfigLayout {
            return ClusterConfigLayout(ConfigPath.Root / namespace / "cluster")
        }
    }
}

interface ClusterTopologyProvider {
    suspend fun current(): ClusterTopology

    fun watch(): Flow<ClusterTopology>
}

class ConfigCenterClusterTopologyProvider(
    private val repository: RuntimeConfigRepository,
    private val layout: ClusterConfigLayout,
) : ClusterTopologyProvider {
    override suspend fun current(): ClusterTopology {
        return repository.children<RuntimeNodeConfig>(layout.nodes).toTopology()
    }

    override fun watch(): Flow<ClusterTopology> {
        return repository.watchChildren<RuntimeNodeConfig>(layout.nodes).map { it.toTopology() }
    }

    private fun io.github.mikai233.asteria.config.center.RuntimeConfigChildrenSnapshot<RuntimeNodeConfig>.toTopology(): ClusterTopology {
        return ClusterTopology(values.values.map { it.value }.sortedBy { it.nodeId })
    }
}

class StaticClusterTopologyProvider(
    private val topology: ClusterTopology,
) : ClusterTopologyProvider {
    override suspend fun current(): ClusterTopology {
        return topology
    }

    override fun watch(): Flow<ClusterTopology> {
        return flowOf(topology)
    }
}

class TypesafeClusterTopologyProvider(
    private val config: Config,
    private val nodesPath: String = DEFAULT_NODES_PATH,
) : ClusterTopologyProvider {
    private val topology: ClusterTopology by lazy { config.readTopology(nodesPath) }

    override suspend fun current(): ClusterTopology {
        return topology
    }

    override fun watch(): Flow<ClusterTopology> {
        return flowOf(topology)
    }

    companion object {
        const val DEFAULT_NODES_PATH: String = "asteria.cluster.nodes"
    }
}

private fun Config.readTopology(nodesPath: String): ClusterTopology {
    if (!hasPath(nodesPath)) {
        error("cluster nodes config path $nodesPath not found")
    }
    return ClusterTopology(
        getConfigList(nodesPath).map { nodeConfig ->
            RuntimeNodeConfig(
                nodeId = nodeConfig.readString("node-id", "nodeId"),
                host = nodeConfig.getString("host"),
                port = nodeConfig.getInt("port"),
                roles = nodeConfig.readStringSet("roles"),
                seed = nodeConfig.readBooleanOrDefault("seed", false),
                attributes = nodeConfig.readStringMapOrDefault("attributes"),
            )
        }.sortedBy { it.nodeId },
    )
}

private fun Config.readString(vararg paths: String): String {
    val path = paths.firstOrNull(::hasPath) ?: error("required config path ${paths.joinToString(" or ")} not found")
    return getString(path)
}

private fun Config.readStringSet(path: String): Set<String> {
    return if (hasPath(path)) {
        getStringList(path).toSet()
    } else {
        emptySet()
    }
}

private fun Config.readBooleanOrDefault(
    path: String,
    default: Boolean,
): Boolean {
    return if (hasPath(path)) getBoolean(path) else default
}

private fun Config.readStringMapOrDefault(path: String): Map<String, String> {
    return if (hasPath(path)) {
        getConfig(path).entrySet().associate { (key, value) -> key to value.unwrapped().toString() }
    } else {
        emptyMap()
    }
}
