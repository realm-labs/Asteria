package io.github.realmlabs.asteria.cluster.config

import io.github.realmlabs.asteria.core.RoleKey
import java.time.Instant

/**
 * Cluster-wide node view used by GM, scripts, patches, and operational pages.
 *
 * Implementations should merge configured topology with runtime/service-discovery status. A configured node that is
 * currently offline should still appear in the snapshot so callers can report missing targets explicitly.
 */
fun interface ClusterViewService {
    suspend fun snapshot(): ClusterViewSnapshot
}

data class ClusterViewSnapshot(
    val nodes: List<ClusterViewNode>,
    val updatedAt: Instant = Instant.now(),
) {
    init {
        val duplicateNodeIds = nodes.mapNotNull { it.nodeId }.groupBy { it }.filterValues { it.size > 1 }.keys
        require(duplicateNodeIds.isEmpty()) { "cluster view contains duplicate node ids: $duplicateNodeIds" }
        val duplicateAddresses = nodes.mapNotNull { it.address }.groupBy { it }.filterValues { it.size > 1 }.keys
        require(duplicateAddresses.isEmpty()) { "cluster view contains duplicate node addresses: $duplicateAddresses" }
    }
}

data class ClusterViewNode(
    val nodeId: String?,
    val address: String?,
    val appName: String,
    val version: String?,
    val roles: Set<RoleKey>,
    val status: ClusterViewNodeStatus,
    val configured: Boolean = true,
    val lastSeenAt: Instant? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        nodeId?.let { require(it.isNotBlank()) { "cluster view node id must not be blank" } }
        address?.let { require(it.isNotBlank()) { "cluster view node address must not be blank" } }
        require(appName.isNotBlank()) { "cluster view app name must not be blank" }
        version?.let { require(it.isNotBlank()) { "cluster view app version must not be blank" } }
        attributes.forEach { (key, _) -> require(key.isNotBlank()) { "cluster view attribute key must not be blank" } }
    }
}

enum class ClusterViewNodeStatus {
    Expected,
    Reachable,
    Unreachable,
    Removed,
}

class TopologyClusterViewService(
    private val topology: ClusterTopologyProvider,
    private val appName: String,
    private val version: String? = null,
) : ClusterViewService {
    init {
        require(appName.isNotBlank()) { "cluster view app name must not be blank" }
        version?.let { require(it.isNotBlank()) { "cluster view app version must not be blank" } }
    }

    override suspend fun snapshot(): ClusterViewSnapshot {
        return ClusterViewSnapshot(
            nodes = topology.current().nodes.map { node ->
                ClusterViewNode(
                    nodeId = node.nodeId,
                    address = "${node.host}:${node.port}",
                    appName = appName,
                    version = version,
                    roles = node.roles.mapTo(linkedSetOf(), ::RoleKey),
                    status = ClusterViewNodeStatus.Expected,
                    configured = true,
                    attributes = node.attributes,
                )
            },
        )
    }
}
