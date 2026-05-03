package io.github.realmlabs.asteria.gm.cluster.pekko.management.spring

import io.github.realmlabs.asteria.gm.cluster.pekko.management.PekkoManagementEndpoint
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Spring properties for GM's Pekko Management HTTP adapter.
 */
@ConfigurationProperties(prefix = "asteria.gm.cluster.pekko-management")
class PekkoManagementGmClusterProperties {
    var enabled: Boolean = true
    var baseUrl: String? = "http://127.0.0.1:7626"
    var endpoints: MutableList<Endpoint> = mutableListOf()

    fun toManagementEndpoints(): List<PekkoManagementEndpoint> {
        val configured = endpoints
            .filter { it.baseUrl.isNotBlank() }
            .map {
                PekkoManagementEndpoint(
                    baseUrl = it.baseUrl,
                    nodeAddress = it.nodeAddress?.takeIf(String::isNotBlank)
                )
            }
        if (configured.isNotEmpty()) {
            return configured
        }
        return listOfNotNull(baseUrl?.takeIf(String::isNotBlank)?.let(::PekkoManagementEndpoint))
    }

    class Endpoint {
        var baseUrl: String = ""
        var nodeAddress: String? = null
    }
}
