package io.github.mikai233.asteria.gm.cluster

import io.github.mikai233.asteria.cluster.config.ClusterTopologyProvider

/**
 * Runtime-facing cluster status exposed to GM tools.
 *
 * The shape is deliberately runtime-neutral. Adapters can enrich nodes through `attributes` without forcing the GM
 * frontend to depend on Pekko, Kubernetes, or a specific registry implementation.
 */
data class GmClusterStatus(
    val nodes: List<GmClusterNode>,
) {
    val roleCounts: Map<String, Int> = nodes
        .flatMap { it.roles }
        .groupingBy { it }
        .eachCount()
}

/**
 * One node visible in the GM cluster view.
 */
data class GmClusterNode(
    val nodeId: String? = null,
    val address: String,
    val status: String,
    val roles: Set<String> = emptySet(),
    val seed: Boolean? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        nodeId?.let { require(it.isNotBlank()) { "GM cluster node id must not be blank" } }
        require(address.isNotBlank()) { "GM cluster node address must not be blank" }
        require(status.isNotBlank()) { "GM cluster node status must not be blank" }
        require(roles.all { it.isNotBlank() }) { "GM cluster node roles must not contain blank values" }
        attributes.forEach { (key, value) ->
            require(key.isNotBlank()) { "GM cluster node attribute key must not be blank" }
            require(value.isNotBlank()) { "GM cluster node attribute value must not be blank" }
        }
    }
}

/**
 * Reads the current cluster status for GM pages and APIs.
 */
fun interface GmClusterStatusService {
    suspend fun current(): GmClusterStatus
}

/**
 * Status service backed by configured topology only.
 *
 * This is useful before a concrete runtime adapter is installed, or in tests where the GM node should display the
 * planned topology without connecting to the cluster runtime.
 */
class TopologyGmClusterStatusService(
    private val topologyProvider: ClusterTopologyProvider,
) : GmClusterStatusService {
    override suspend fun current(): GmClusterStatus {
        val topology = topologyProvider.current()
        return GmClusterStatus(
            nodes = topology.nodes.map { node ->
                GmClusterNode(
                    nodeId = node.nodeId,
                    address = "${node.host}:${node.port}",
                    status = "configured",
                    roles = node.roles,
                    seed = node.seed,
                    attributes = node.attributes,
                )
            },
        )
    }
}
