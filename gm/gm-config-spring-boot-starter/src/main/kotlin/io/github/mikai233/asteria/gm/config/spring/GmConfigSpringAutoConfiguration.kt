package io.github.mikai233.asteria.gm.config.spring

import io.github.mikai233.asteria.cluster.config.ClusterConfigControlService
import io.github.mikai233.asteria.config.ConfigService
import io.github.mikai233.asteria.config.ConfigReloadMonitor
import io.github.mikai233.asteria.gm.config.ConfigRowProjector
import io.github.mikai233.asteria.gm.config.GmConfigInspector
import io.github.mikai233.asteria.gm.config.ReflectionConfigRowProjector
import io.github.mikai233.asteria.gm.config.SnapshotGmConfigInspector
import io.github.mikai233.asteria.gm.spring.GmEndpointSupport
import io.github.mikai233.asteria.gm.spring.GmSpringAutoConfiguration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration for config GM HTTP APIs.
 */
@AutoConfiguration(after = [GmSpringAutoConfiguration::class])
@ConditionalOnProperty(
    prefix = "asteria.gm.config.web",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class GmConfigSpringAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun gmConfigRowProjector(): ConfigRowProjector {
        return ReflectionConfigRowProjector()
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ConfigService::class)
    fun snapshotGmConfigInspector(
        configService: ConfigService,
        projector: ConfigRowProjector,
        reloadMonitor: ObjectProvider<ConfigReloadMonitor>,
    ): GmConfigInspector {
        return SnapshotGmConfigInspector(configService, projector, reloadMonitor.getIfAvailable())
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(GmConfigInspector::class)
    fun gmConfigController(
        inspector: GmConfigInspector,
        endpointSupport: GmEndpointSupport,
        clusterControl: ObjectProvider<ClusterConfigControlService>,
    ): GmConfigController {
        return GmConfigController(inspector, endpointSupport, clusterControl.getIfAvailable())
    }
}
