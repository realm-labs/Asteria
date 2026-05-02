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
 * Runtime-facing access to the raw cluster management status.
 *
 * This is intentionally separate from [GmClusterStatusService]. GM pages should prefer the normalized model, while
 * troubleshooting tools can expose the raw response from Akka/Pekko Management behind a stronger permission.
 */
fun interface GmClusterRawStatusService {
    suspend fun rawStatus(): String
}

/**
 * Cluster membership control surface exposed to GM tools.
 *
 * Implementations must treat these methods as requests submitted to the runtime management plane, not as proof that
 * the cluster has already converged. Callers should read status again after the operation if they need confirmation.
 */
interface GmClusterControlService {
    suspend fun leave(request: GmClusterLeaveRequest): GmClusterOperationResult

    suspend fun join(request: GmClusterJoinRequest): GmClusterOperationResult

    suspend fun down(request: GmClusterDownRequest): GmClusterOperationResult
}

/**
 * Requests a graceful cluster leave for `address`.
 */
data class GmClusterLeaveRequest(
    val address: String,
    val requestedBy: String,
    val reason: String,
) {
    init {
        require(address.isNotBlank()) { "GM cluster leave address must not be blank" }
        require(requestedBy.isNotBlank()) { "GM cluster leave requester must not be blank" }
        require(reason.isNotBlank()) { "GM cluster leave reason must not be blank" }
    }
}

/**
 * Requests that `nodeAddress` joins the cluster through an optional `seedAddress`.
 *
 * In discovery/bootstrap deployments, adding capacity is usually an orchestration action. This request is for
 * adapters that can reach the joining node's management endpoint directly, such as static seed-node deployments.
 */
data class GmClusterJoinRequest(
    val nodeAddress: String,
    val seedAddress: String? = null,
    val requestedBy: String,
    val reason: String,
) {
    init {
        require(nodeAddress.isNotBlank()) { "GM cluster join node address must not be blank" }
        seedAddress?.let { require(it.isNotBlank()) { "GM cluster join seed address must not be blank" } }
        require(requestedBy.isNotBlank()) { "GM cluster join requester must not be blank" }
        require(reason.isNotBlank()) { "GM cluster join reason must not be blank" }
    }
}

/**
 * Requests a force-down for `address`.
 *
 * Downing is intentionally modeled separately from leave because it can be destructive during partitions.
 */
data class GmClusterDownRequest(
    val address: String,
    val requestedBy: String,
    val reason: String,
    val confirmed: Boolean,
) {
    init {
        require(address.isNotBlank()) { "GM cluster down address must not be blank" }
        require(requestedBy.isNotBlank()) { "GM cluster down requester must not be blank" }
        require(reason.isNotBlank()) { "GM cluster down reason must not be blank" }
    }
}

/**
 * Result returned after a cluster control request has been accepted or rejected by the management plane.
 */
data class GmClusterOperationResult(
    val action: String,
    val targetAddress: String,
    val accepted: Boolean,
    val message: String,
    val managementEndpoint: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(action.isNotBlank()) { "GM cluster operation action must not be blank" }
        require(targetAddress.isNotBlank()) { "GM cluster operation target address must not be blank" }
        require(message.isNotBlank()) { "GM cluster operation message must not be blank" }
        managementEndpoint?.let { require(it.isNotBlank()) { "GM cluster operation endpoint must not be blank" } }
        attributes.forEach { (key, value) ->
            require(key.isNotBlank()) { "GM cluster operation attribute key must not be blank" }
            require(value.isNotBlank()) { "GM cluster operation attribute value must not be blank" }
        }
    }
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
