package io.github.realmlabs.asteria.gm.cluster.pekko.management.spring

import io.github.realmlabs.asteria.gm.cluster.GmClusterControlService
import io.github.realmlabs.asteria.gm.cluster.GmClusterRawStatusService
import io.github.realmlabs.asteria.gm.cluster.GmClusterStatusService
import io.github.realmlabs.asteria.gm.cluster.pekko.management.PekkoManagementEndpointResolver
import io.github.realmlabs.asteria.gm.cluster.pekko.management.PekkoManagementGmClusterControlService
import io.github.realmlabs.asteria.gm.cluster.pekko.management.PekkoManagementGmClusterStatusService
import io.github.realmlabs.asteria.gm.cluster.pekko.management.PekkoManagementHttpClient
import io.github.realmlabs.asteria.gm.cluster.spring.GmClusterSpringAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration for exposing Pekko Management HTTP through GM cluster APIs.
 */
@AutoConfiguration(before = [GmClusterSpringAutoConfiguration::class])
@ConditionalOnProperty(
    prefix = "asteria.gm.cluster.pekko-management",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(PekkoManagementGmClusterProperties::class)
class PekkoManagementGmClusterSpringAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun pekkoManagementEndpointResolver(
        properties: PekkoManagementGmClusterProperties,
    ): PekkoManagementEndpointResolver {
        return PekkoManagementEndpointResolver(properties.toManagementEndpoints())
    }

    @Bean
    @ConditionalOnMissingBean
    fun pekkoManagementHttpClient(): PekkoManagementHttpClient {
        return PekkoManagementHttpClient()
    }

    @Bean
    @ConditionalOnMissingBean
    fun pekkoManagementGmClusterStatusService(
        client: PekkoManagementHttpClient,
        endpoints: PekkoManagementEndpointResolver,
    ): PekkoManagementGmClusterStatusService {
        return PekkoManagementGmClusterStatusService(client, endpoints)
    }

    @Bean
    @ConditionalOnMissingBean(GmClusterStatusService::class)
    fun gmClusterStatusService(service: PekkoManagementGmClusterStatusService): GmClusterStatusService {
        return service
    }

    @Bean
    @ConditionalOnMissingBean(GmClusterRawStatusService::class)
    fun gmClusterRawStatusService(service: PekkoManagementGmClusterStatusService): GmClusterRawStatusService {
        return service
    }

    @Bean
    @ConditionalOnMissingBean(GmClusterControlService::class)
    fun gmClusterControlService(
        client: PekkoManagementHttpClient,
        endpoints: PekkoManagementEndpointResolver,
    ): GmClusterControlService {
        return PekkoManagementGmClusterControlService(client, endpoints)
    }
}
