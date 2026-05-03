package io.github.realmlabs.asteria.gm.config.spring

import io.github.realmlabs.asteria.cluster.config.ClusterConfigReloadTarget
import kotlin.time.Duration.Companion.milliseconds

/**
 * HTTP request for a cluster-wide config reload.
 */
data class GmClusterConfigReloadHttpRequest(
    val target: String = "all",
    val role: String? = null,
    val nodeIds: Set<String> = emptySet(),
    val addresses: Set<String> = emptySet(),
    val timeoutMillis: Long = 10_000,
) {
    init {
        require(target.isNotBlank()) { "GM cluster config reload target must not be blank" }
        require(timeoutMillis > 0) { "GM cluster config reload timeout must be positive" }
    }

    fun reloadTarget(): ClusterConfigReloadTarget {
        return when (target.lowercase()) {
            "all" -> ClusterConfigReloadTarget.All
            "role" -> ClusterConfigReloadTarget.Role(
                role ?: error("GM cluster config reload role target requires role"),
            )

            "nodes" -> ClusterConfigReloadTarget.Nodes(nodeIds)
            "addresses" -> ClusterConfigReloadTarget.Addresses(addresses)
            else -> error("unsupported GM cluster config reload target $target")
        }
    }

    fun timeout() = timeoutMillis.milliseconds
}
