package io.github.mikai233.asteria.script.job.spring

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Spring Boot configuration properties for script job execution.
 */
@ConfigurationProperties(prefix = "asteria.script.job")
class ScriptJobSpringProperties {
    /**
     * Stable worker id used in item leases and audit records. A random id is generated when omitted.
     */
    var workerId: String? = null

    /**
     * Whether the starter should create an in-memory repository when no durable repository bean exists.
     */
    var inMemoryRepositoryEnabled: Boolean = true

    /**
     * Whether recoverable jobs are resumed after the Spring context is ready.
     */
    var recoverOnStart: Boolean = true

    /**
     * Maximum jobs to scan during each recovery pass.
     */
    var recoveryLimit: Int = 100

    /**
     * Timeout used for recovered job item executions.
     */
    var recoveryTimeout: Duration = Duration.ofSeconds(3)

    /**
     * Periodic recovery interval. Set to null to disable the background recovery scan.
     */
    var recoveryScanInterval: Duration? = Duration.ofSeconds(30)

    /**
     * Number of pending items claimed in one repository call.
     */
    var claimBatchSize: Int = 64

    /**
     * Lease duration for claimed or running items.
     */
    var leaseDuration: Duration = Duration.ofSeconds(30)

    /**
     * Interval for renewing running item leases.
     */
    var leaseRenewalInterval: Duration = Duration.ofSeconds(10)

    /**
     * Global item concurrency. When a distributed permit repository exists, this is cluster-wide.
     */
    var maxConcurrentItems: Int = 256

    /**
     * Whether a shared permit repository should be used when one is available.
     */
    var distributedPermitsEnabled: Boolean = true

    /**
     * Shared permit pool name.
     */
    var permitPool: String = "script-job-items"

    /**
     * Lease duration for distributed permits.
     */
    var permitLeaseDuration: Duration = Duration.ofSeconds(30)

    /**
     * Interval for renewing distributed permits.
     */
    var permitRenewalInterval: Duration = Duration.ofSeconds(10)

    /**
     * Delay before retrying a distributed permit acquisition.
     */
    var permitAcquireRetryDelay: Duration = Duration.ofMillis(100)

    /**
     * Optional local concurrency lanes by script engine.
     */
    var engineConcurrency: MutableMap<String, Int> = linkedMapOf()

    /**
     * Optional local concurrency lanes by GM operator id.
     */
    var operatorConcurrency: MutableMap<String, Int> = linkedMapOf()

    /**
     * Optional local concurrency lanes by target type, such as `entity`, `nodes`, or `actor-paths`.
     */
    var targetTypeConcurrency: MutableMap<String, Int> = linkedMapOf()
}
