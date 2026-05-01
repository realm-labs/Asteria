package io.github.mikai233.asteria.cluster.config

import io.github.mikai233.asteria.config.center.ConfigPath
import io.github.mikai233.asteria.config.center.RuntimeConfigRepository
import kotlinx.coroutines.flow.Flow
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
    val seedNodes: List<RuntimeNodeConfig> get() = nodes.filter { it.seed }

    fun nodesByRole(role: String): List<RuntimeNodeConfig> {
        return nodes.filter { role in it.roles }
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
