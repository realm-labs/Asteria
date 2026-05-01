package io.github.mikai233.asteria.gm.spring

import io.github.mikai233.asteria.gm.core.DefaultGmPolicyEvaluator
import io.github.mikai233.asteria.gm.core.GmAuditSink
import io.github.mikai233.asteria.gm.core.GmFeature
import io.github.mikai233.asteria.gm.core.GmFeatureRegistry
import io.github.mikai233.asteria.gm.core.GmPolicyEvaluator
import io.github.mikai233.asteria.gm.core.NoopGmAuditSink
import io.github.mikai233.asteria.gm.core.discoverGmFeatures
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnProperty(prefix = "asteria.gm", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(GmSpringProperties::class)
class GmSpringAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun gmFeatureRegistry(features: List<GmFeature>): GmFeatureRegistry {
        return GmFeatureRegistry(discoverGmFeatures() + features)
    }

    @Bean
    @ConditionalOnMissingBean
    fun gmPolicyEvaluator(): GmPolicyEvaluator {
        return DefaultGmPolicyEvaluator()
    }

    @Bean
    @ConditionalOnMissingBean
    fun gmAuditSink(): GmAuditSink {
        return NoopGmAuditSink
    }

    @Bean
    @ConditionalOnMissingBean
    fun gmFeatureController(registry: GmFeatureRegistry): GmFeatureController {
        return GmFeatureController(registry)
    }
}
