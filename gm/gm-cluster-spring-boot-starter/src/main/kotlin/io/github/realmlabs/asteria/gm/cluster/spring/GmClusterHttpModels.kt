package io.github.realmlabs.asteria.gm.cluster.spring

/**
 * HTTP request for gracefully removing a node from the cluster.
 */
data class GmClusterLeaveHttpRequest(
    val address: String,
    val reason: String,
) {
    init {
        require(address.isNotBlank()) { "GM cluster leave address must not be blank" }
        require(reason.isNotBlank()) { "GM cluster leave reason must not be blank" }
    }
}

/**
 * HTTP request for joining a node to the cluster.
 */
data class GmClusterJoinHttpRequest(
    val nodeAddress: String,
    val seedAddress: String? = null,
    val reason: String,
) {
    init {
        require(nodeAddress.isNotBlank()) { "GM cluster join node address must not be blank" }
        seedAddress?.let { require(it.isNotBlank()) { "GM cluster join seed address must not be blank" } }
        require(reason.isNotBlank()) { "GM cluster join reason must not be blank" }
    }
}

/**
 * HTTP request for forcibly downing a cluster node.
 */
data class GmClusterDownHttpRequest(
    val address: String,
    val reason: String,
    val confirmed: Boolean = false,
) {
    init {
        require(address.isNotBlank()) { "GM cluster down address must not be blank" }
        require(reason.isNotBlank()) { "GM cluster down reason must not be blank" }
    }
}
