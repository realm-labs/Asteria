package io.github.mikai233.asteria.gm.cluster.pekko.management

/**
 * One reachable Pekko Management HTTP endpoint.
 *
 * `nodeAddress` is optional for read-only status queries, but it is important for join operations because the join
 * command must be sent to the node that should perform the join.
 */
data class PekkoManagementEndpoint(
    val baseUrl: String,
    val nodeAddress: String? = null,
) {
    init {
        require(baseUrl.isNotBlank()) { "Pekko Management base URL must not be blank" }
        nodeAddress?.let { require(it.isNotBlank()) { "Pekko Management node address must not be blank" } }
    }

    val normalizedBaseUrl: String = baseUrl.trimEnd('/')
}

/**
 * Resolves which Management endpoint should receive a cluster operation.
 */
class PekkoManagementEndpointResolver(
    endpoints: List<PekkoManagementEndpoint>,
) {
    private val endpoints: List<PekkoManagementEndpoint> = endpoints.also {
        require(it.isNotEmpty()) { "At least one Pekko Management endpoint is required" }
    }

    fun statusEndpoint(): PekkoManagementEndpoint = endpoints.first()

    fun controlEndpoint(address: String): PekkoManagementEndpoint {
        return endpointFor(address) ?: endpoints.first()
    }

    fun joinEndpoint(nodeAddress: String): PekkoManagementEndpoint {
        return endpointFor(nodeAddress)
            ?: error("No Pekko Management endpoint is configured for joining node $nodeAddress")
    }

    private fun endpointFor(address: String): PekkoManagementEndpoint? {
        return endpoints.firstOrNull { it.nodeAddress == address }
    }
}
