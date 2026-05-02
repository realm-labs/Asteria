package io.github.mikai233.asteria.gm.spring

import io.github.mikai233.asteria.gm.core.*
import io.github.mikai233.asteria.observability.Metrics
import io.github.mikai233.asteria.observability.NoopMetrics
import org.springframework.beans.factory.ObjectProvider
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
    fun gmPrincipalResolver(): GmPrincipalResolver {
        return NoopGmPrincipalResolver
    }

    @Bean
    @ConditionalOnMissingBean
    fun gmEndpointSupport(
        principalResolver: GmPrincipalResolver,
        policyEvaluator: GmPolicyEvaluator,
        auditSink: GmAuditSink,
        metrics: ObjectProvider<Metrics>,
    ): GmEndpointSupport {
        return GmEndpointSupport(principalResolver, policyEvaluator, auditSink, metrics.ifAvailable ?: NoopMetrics)
    }

    @Bean
    @ConditionalOnMissingBean
    fun gmWebExceptionHandler(): GmWebExceptionHandler {
        return GmWebExceptionHandler()
    }

    @Bean
    @ConditionalOnMissingBean
    fun gmFeatureController(registry: GmFeatureRegistry): GmFeatureController {
        return GmFeatureController(registry)
    }
}
