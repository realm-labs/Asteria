package io.github.realmlabs.asteria.gm.cluster.spring

import io.github.realmlabs.asteria.cluster.config.ClusterTopologyProvider
import io.github.realmlabs.asteria.cluster.config.ClusterViewService
import io.github.realmlabs.asteria.gm.cluster.*
import io.github.realmlabs.asteria.gm.spring.GmEndpointSupport
import io.github.realmlabs.asteria.gm.spring.GmSpringAutoConfiguration
import org.springframework.beans.factory.ObjectProvider
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
    @ConditionalOnBean(ClusterViewService::class)
    fun clusterViewGmClusterStatusService(clusterView: ClusterViewService): GmClusterStatusService {
        return ClusterViewGmClusterStatusService(clusterView)
    }

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
        rawStatusService: ObjectProvider<GmClusterRawStatusService>,
        controlService: ObjectProvider<GmClusterControlService>,
    ): GmClusterController {
        return GmClusterController(
            statusService = statusService,
            endpoints = endpointSupport,
            rawStatusService = rawStatusService.ifAvailable,
            controlService = controlService.ifAvailable,
        )
    }
}
