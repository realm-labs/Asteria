package io.github.mikai233.asteria.script.job.spring

import io.github.mikai233.asteria.observability.Metrics
import io.github.mikai233.asteria.observability.NoopMetrics
import io.github.mikai233.asteria.observability.NoopTracer
import io.github.mikai233.asteria.observability.Tracer
import io.github.mikai233.asteria.script.ScriptRuntime
import io.github.mikai233.asteria.script.job.*
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.boot.autoconfigure.condition.*
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import java.util.*
import kotlin.time.toKotlinDuration

/**
 * Spring Boot auto-configuration for script job execution.
 */
@AutoConfiguration
@AutoConfigureAfter(name = ["io.github.mikai233.asteria.script.job.mongodb.spring.ScriptJobMongoSpringAutoConfiguration"])
@ConditionalOnClass(ScriptJobService::class)
@ConditionalOnProperty(
    prefix = "asteria.script.job",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(ScriptJobSpringProperties::class)
class ScriptJobSpringAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["asteriaScriptJobCoroutineScope"])
    fun asteriaScriptJobCoroutineScope(): ScriptJobCoroutineScope {
        return ScriptJobCoroutineScope()
    }

    @Bean
    @ConditionalOnMissingBean(value = [ScriptJobRepository::class], search = SearchStrategy.CURRENT)
    @ConditionalOnProperty(
        prefix = "asteria.script.job",
        name = ["in-memory-repository-enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun inMemoryScriptJobRepository(): InMemoryScriptJobRepository {
        return InMemoryScriptJobRepository()
    }

    @Bean
    @ConditionalOnMissingBean(value = [ScriptJobExecutionLimiter::class], search = SearchStrategy.CURRENT)
    fun scriptJobExecutionLimiter(
        properties: ScriptJobSpringProperties,
        permitRepository: ObjectProvider<ScriptJobPermitRepository>,
        @Qualifier("asteriaScriptJobCoroutineScope") scope: ScriptJobCoroutineScope,
    ): ScriptJobExecutionLimiter {
        val sharedPermits = permitRepository.ifAvailable
        if (properties.distributedPermitsEnabled && sharedPermits != null) {
            return RepositoryScriptJobExecutionLimiter(
                repository = sharedPermits,
                scope = scope,
                pool = properties.permitPool,
                maxConcurrentItems = properties.maxConcurrentItems,
                leaseDuration = properties.permitLeaseDuration.toKotlinDuration(),
                renewalInterval = properties.permitRenewalInterval.toKotlinDuration(),
                retryDelay = properties.permitAcquireRetryDelay.toKotlinDuration(),
            )
        }
        return SemaphoreScriptJobExecutionLimiter(
            globalLimit = properties.maxConcurrentItems,
            engineLimits = properties.engineConcurrency,
            operatorLimits = properties.operatorConcurrency,
            targetTypeLimits = properties.targetTypeConcurrency,
        )
    }

    @Bean
    @ConditionalOnBean(ScriptRuntime::class, ScriptJobRepository::class)
    @ConditionalOnMissingBean(value = [ScriptJobService::class], search = SearchStrategy.CURRENT)
    fun scriptJobService(
        runtime: ScriptRuntime,
        repository: ScriptJobRepository,
        properties: ScriptJobSpringProperties,
        executionLimiter: ScriptJobExecutionLimiter,
        @Qualifier("asteriaScriptJobCoroutineScope") scope: ScriptJobCoroutineScope,
        tracer: ObjectProvider<Tracer>,
        metrics: ObjectProvider<Metrics>,
        auditSink: ObjectProvider<ScriptJobAuditSink>,
    ): ScriptJobService {
        return ScriptJobService(
            runtime = runtime,
            repository = repository,
            scope = scope,
            tracer = tracer.ifAvailable ?: NoopTracer,
            metrics = metrics.ifAvailable ?: NoopMetrics,
            workerId = properties.workerId ?: "script-job-${UUID.randomUUID()}",
            claimBatchSize = properties.claimBatchSize,
            leaseDuration = properties.leaseDuration.toKotlinDuration(),
            leaseRenewalInterval = properties.leaseRenewalInterval.toKotlinDuration(),
            executionLimiter = executionLimiter,
            auditSink = auditSink.ifAvailable ?: io.github.mikai233.asteria.script.job.NoopScriptJobAuditSink,
        )
    }

    @Bean
    @ConditionalOnBean(ScriptJobService::class)
    @ConditionalOnMissingBean(value = [ScriptJobServiceLifecycle::class], search = SearchStrategy.CURRENT)
    fun scriptJobServiceLifecycle(
        service: ScriptJobService,
        @Qualifier("asteriaScriptJobCoroutineScope") scope: ScriptJobCoroutineScope,
        properties: ScriptJobSpringProperties,
    ): ScriptJobServiceLifecycle {
        return ScriptJobServiceLifecycle(service, scope, properties)
    }
}
