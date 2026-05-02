package io.github.mikai233.asteria.cluster.config

import io.github.mikai233.asteria.config.ConfigRevision
import java.io.Serializable
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Cluster-wide config control surface.
 *
 * Implementations fan out status and reload requests to runtime nodes. Reload is intentionally modeled as an
 * operational request, not as a cluster transaction: callers should inspect each node result and retry failed nodes
 * instead of expecting an all-or-nothing rollback.
 */
interface ClusterConfigControlService {
    suspend fun statuses(timeout: Duration = 5.seconds): List<ClusterConfigNodeStatus>

    suspend fun reload(
        target: ClusterConfigReloadTarget = ClusterConfigReloadTarget.All,
        timeout: Duration = 10.seconds,
    ): ClusterConfigReloadResult

    suspend fun checkConsistency(timeout: Duration = 5.seconds): ClusterConfigRevisionConsistency {
        return ClusterConfigRevisionConsistency(statuses(timeout))
    }
}

/**
 * Target selection for a cluster config reload request.
 */
sealed interface ClusterConfigReloadTarget : Serializable {
    data object All : ClusterConfigReloadTarget

    data class Role(val role: String) : ClusterConfigReloadTarget {
        init {
            require(role.isNotBlank()) { "cluster config reload role must not be blank" }
        }
    }

    data class Nodes(val nodeIds: Set<String>) : ClusterConfigReloadTarget {
        init {
            require(nodeIds.isNotEmpty()) { "cluster config reload node ids must not be empty" }
            require(nodeIds.all { it.isNotBlank() }) { "cluster config reload node ids must not contain blank values" }
        }
    }

    data class Addresses(val addresses: Set<String>) : ClusterConfigReloadTarget {
        init {
            require(addresses.isNotEmpty()) { "cluster config reload addresses must not be empty" }
            require(addresses.all { it.isNotBlank() }) { "cluster config reload addresses must not contain blank values" }
        }
    }
}

/**
 * Config status observed for one runtime node.
 */
data class ClusterConfigNodeStatus(
    val nodeId: String?,
    val address: String,
    val roles: Set<String> = emptySet(),
    val revision: ConfigRevision?,
    val reachable: Boolean = true,
    val message: String? = null,
) : Serializable {
    init {
        nodeId?.let { require(it.isNotBlank()) { "cluster config node id must not be blank" } }
        require(address.isNotBlank()) { "cluster config node address must not be blank" }
        require(roles.all { it.isNotBlank() }) { "cluster config node roles must not contain blank values" }
        message?.let { require(it.isNotBlank()) { "cluster config node status message must not be blank" } }
    }
}

data class ClusterConfigReloadResult(
    val target: ClusterConfigReloadTarget,
    val requestedAt: Instant,
    val results: List<ClusterConfigNodeReloadResult>,
) : Serializable {
    val succeeded: Boolean =
        results.isNotEmpty() && results.all { it.status == ClusterConfigNodeReloadStatus.Succeeded }
}

data class ClusterConfigNodeReloadResult(
    val nodeId: String?,
    val address: String,
    val roles: Set<String> = emptySet(),
    val previousRevision: ConfigRevision?,
    val currentRevision: ConfigRevision?,
    val status: ClusterConfigNodeReloadStatus,
    val message: String? = null,
) : Serializable {
    init {
        nodeId?.let { require(it.isNotBlank()) { "cluster config reload result node id must not be blank" } }
        require(address.isNotBlank()) { "cluster config reload result address must not be blank" }
        require(roles.all { it.isNotBlank() }) { "cluster config reload result roles must not contain blank values" }
        message?.let { require(it.isNotBlank()) { "cluster config reload result message must not be blank" } }
    }
}

enum class ClusterConfigNodeReloadStatus {
    Succeeded,
    Failed,
    Timeout,
    Unreachable,
    Skipped,
}

data class ClusterConfigRevisionConsistency(
    val statuses: List<ClusterConfigNodeStatus>,
) : Serializable {
    val reachableNodes: List<ClusterConfigNodeStatus> = statuses.filter { it.reachable }

    val revisionGroups: List<ClusterConfigRevisionGroup> = reachableNodes
        .groupBy { it.revision }
        .map { (revision, nodes) -> ClusterConfigRevisionGroup(revision, nodes.sortedBy { it.address }) }
        .sortedBy { it.revision?.version.orEmpty() }

    val consistent: Boolean = statuses.isNotEmpty() &&
            statuses.all { it.reachable } &&
            revisionGroups.size <= 1
}

data class ClusterConfigRevisionGroup(
    val revision: ConfigRevision?,
    val nodes: List<ClusterConfigNodeStatus>,
) : Serializable
