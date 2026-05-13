package io.github.realmlabs.asteria.gm.configcenter.spring

import io.github.realmlabs.asteria.config.center.ConfigStore
import io.github.realmlabs.asteria.gm.configcenter.ConfigCenterBrowser
import io.github.realmlabs.asteria.gm.configcenter.ConfigCenterBrowserAccessPolicy
import io.github.realmlabs.asteria.gm.configcenter.ConfigEntryDecoder
import io.github.realmlabs.asteria.gm.spring.GmEndpointSupport
import io.github.realmlabs.asteria.gm.spring.GmSpringAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration for the read-only ConfigCenter GM browser.
 */
@AutoConfiguration(after = [GmSpringAutoConfiguration::class])
@ConditionalOnProperty(
    prefix = "asteria.gm.config-center.web",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(GmConfigCenterProperties::class)
class GmConfigCenterSpringAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun configCenterBrowserAccessPolicy(properties: GmConfigCenterProperties): ConfigCenterBrowserAccessPolicy {
        return ConfigCenterBrowserAccessPolicy.fromStrings(
            allowedRoots = properties.allowedRoots,
            denyPatterns = properties.denyPatterns,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ConfigStore::class)
    fun configCenterBrowser(
        store: ConfigStore,
        accessPolicy: ConfigCenterBrowserAccessPolicy,
        decoders: List<ConfigEntryDecoder>,
        properties: GmConfigCenterProperties,
    ): ConfigCenterBrowser {
        return ConfigCenterBrowser(
            store = store,
            accessPolicy = accessPolicy,
            decoders = decoders,
            previewLimitBytes = properties.previewLimitBytes,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ConfigCenterBrowser::class)
    fun gmConfigCenterController(
        browser: ConfigCenterBrowser,
        endpoints: GmEndpointSupport,
    ): GmConfigCenterController {
        return GmConfigCenterController(browser, endpoints)
    }

    @Bean
    @ConditionalOnMissingBean
    fun gmConfigCenterExceptionHandler(): GmConfigCenterExceptionHandler {
        return GmConfigCenterExceptionHandler()
    }
}
