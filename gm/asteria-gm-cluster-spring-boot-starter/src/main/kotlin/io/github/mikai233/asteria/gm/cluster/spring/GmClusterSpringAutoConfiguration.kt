package io.github.mikai233.asteria.gm.cluster.spring

import io.github.mikai233.asteria.cluster.config.ClusterTopologyProvider
import io.github.mikai233.asteria.gm.cluster.GmClusterStatusService
import io.github.mikai233.asteria.gm.cluster.TopologyGmClusterStatusService
import io.github.mikai233.asteria.gm.spring.GmEndpointSupport
import io.github.mikai233.asteria.gm.spring.GmSpringAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration for cluster GM HTTP APIs.
 */
@AutoConfiguration(after = [GmSpringAutoConfiguration::class])
@ConditionalOnProperty(
    prefix = "asteria.gm.cluster.web",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class GmClusterSpringAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ClusterTopologyProvider::class)
    fun topologyGmClusterStatusService(topologyProvider: ClusterTopologyProvider): GmClusterStatusService {
        return TopologyGmClusterStatusService(topologyProvider)
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(GmClusterStatusService::class)
    fun gmClusterController(
        statusService: GmClusterStatusService,
        endpointSupport: GmEndpointSupport,
    ): GmClusterController {
        return GmClusterController(statusService, endpointSupport)
    }
}
