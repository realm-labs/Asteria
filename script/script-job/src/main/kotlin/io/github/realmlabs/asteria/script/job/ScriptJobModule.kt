package io.github.realmlabs.asteria.script.job

import io.github.realmlabs.asteria.core.AsteriaDsl
import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import io.github.realmlabs.asteria.observability.NoopTracer
import io.github.realmlabs.asteria.observability.Tracer
import io.github.realmlabs.asteria.script.ScriptCancellationProvider
import io.github.realmlabs.asteria.script.ScriptRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ScriptJobModule private constructor(
    private val options: ScriptJobModuleOptions,
) : AsteriaModule {
    override val name: String = "script-job"

    private var scope: CoroutineScope? = null

    override suspend fun install(context: ModuleContext) {
        val repository = options.repository ?: InMemoryScriptJobRepository()
        context.services.register(ScriptJobRepository::class, repository)
        options.permitRepository?.let { context.services.register(ScriptJobPermitRepository::class, it) }
        context.services.register(ScriptCancellationProvider::class, ScriptJobCancellationProvider(repository))
        context.services.register(ScriptJobModuleOptions::class, options)
    }

    override suspend fun start(context: ModuleContext) {
        val jobScope = CoroutineScope(SupervisorJob())
        val permitRepository = options.permitRepository ?: context.services.find<ScriptJobPermitRepository>()
        val service = ScriptJobService(
            runtime = context.services.get<ScriptRuntime>(),
            repository = context.services.get(),
            scope = jobScope,
            tracer = context.services.find<Tracer>() ?: NoopTracer,
            metrics = context.services.find<Metrics>() ?: NoopMetrics,
            claimBatchSize = options.claimBatchSize,
            leaseDuration = options.leaseDuration,
            leaseRenewalInterval = options.leaseRenewalInterval,
            executionLimiter = options.executionLimiter ?: permitRepository?.let {
                RepositoryScriptJobExecutionLimiter(
                    repository = it,
                    pool = options.permitPool,
                    maxConcurrentItems = options.maxConcurrentItems,
                    leaseDuration = options.permitLeaseDuration,
                    renewalInterval = options.permitRenewalInterval,
                    retryDelay = options.permitAcquireRetryDelay,
                )
            } ?: SemaphoreScriptJobExecutionLimiter(
                globalLimit = options.maxConcurrentItems,
                engineLimits = options.engineConcurrency,
                operatorLimits = options.operatorConcurrency,
                targetTypeLimits = options.targetTypeConcurrency,
            ),
            auditSink = options.auditSink ?: context.services.find<ScriptJobAuditSink>() ?: NoopScriptJobAuditSink,
        )
        scope = jobScope
        context.services.register(ScriptJobService::class, service)
        if (options.recoverOnStart) {
            jobScope.launch {
                service.resumeIncompleteJobs(
                    timeout = options.recoveryTimeout,
                    limit = options.recoveryLimit,
                )
            }
        }
        options.recoveryScanInterval?.let { interval ->
            service.startRecoveryLoop(
                timeout = options.recoveryTimeout,
                limit = options.recoveryLimit,
                interval = interval,
            )
        }
    }

    override suspend fun stop(context: ModuleContext) {
        scope?.cancel()
        scope = null
    }

    companion object {
        operator fun invoke(configure: ScriptJobModuleBuilder.() -> Unit = {}): ScriptJobModule {
            return ScriptJobModule(ScriptJobModuleBuilder().apply(configure).build())
        }
    }
}

data class ScriptJobModuleOptions(
    val repository: ScriptJobRepository?,
    val recoverOnStart: Boolean,
    val recoveryLimit: Int,
    val recoveryTimeout: Duration,
    val recoveryScanInterval: Duration?,
    val claimBatchSize: Int,
    val leaseDuration: Duration,
    val leaseRenewalInterval: Duration,
    val maxConcurrentItems: Int,
    val permitPool: String,
    val permitLeaseDuration: Duration,
    val permitRenewalInterval: Duration,
    val permitAcquireRetryDelay: Duration,
    val engineConcurrency: Map<String, Int>,
    val operatorConcurrency: Map<String, Int>,
    val targetTypeConcurrency: Map<String, Int>,
    val permitRepository: ScriptJobPermitRepository?,
    val executionLimiter: ScriptJobExecutionLimiter?,
    val auditSink: ScriptJobAuditSink?,
)

@AsteriaDsl
class ScriptJobModuleBuilder {
    private var repository: ScriptJobRepository? = null
    private var recoverOnStart: Boolean = true
    private var recoveryLimit: Int = 100
    private var recoveryTimeout: Duration = 3.seconds
    private var recoveryScanInterval: Duration? = 30.seconds
    private var claimBatchSize: Int = 64
    private var leaseDuration: Duration = 30.seconds
    private var leaseRenewalInterval: Duration = 10.seconds
    private var maxConcurrentItems: Int = 256
    private var permitPool: String = "script-job-items"
    private var permitLeaseDuration: Duration = 30.seconds
    private var permitRenewalInterval: Duration = 10.seconds
    private var permitAcquireRetryDelay: Duration = 100.milliseconds
    private val engineConcurrency: MutableMap<String, Int> = linkedMapOf()
    private val operatorConcurrency: MutableMap<String, Int> = linkedMapOf()
    private val targetTypeConcurrency: MutableMap<String, Int> = linkedMapOf()
    private var permitRepository: ScriptJobPermitRepository? = null
    private var executionLimiter: ScriptJobExecutionLimiter? = null
    private var auditSink: ScriptJobAuditSink? = null

    fun repository(repository: ScriptJobRepository) {
        this.repository = repository
    }

    fun recoverOnStart(enabled: Boolean) {
        this.recoverOnStart = enabled
    }

    fun recoveryLimit(limit: Int) {
        this.recoveryLimit = limit
    }

    fun recoveryTimeout(timeout: Duration) {
        this.recoveryTimeout = timeout
    }

    fun recoveryScanInterval(interval: Duration?) {
        this.recoveryScanInterval = interval
    }

    fun claimBatchSize(size: Int) {
        this.claimBatchSize = size
    }

    fun leaseDuration(duration: Duration) {
        this.leaseDuration = duration
    }

    fun leaseRenewalInterval(interval: Duration) {
        this.leaseRenewalInterval = interval
    }

    fun maxConcurrentItems(limit: Int) {
        this.maxConcurrentItems = limit
    }

    fun permitPool(pool: String) {
        this.permitPool = pool
    }

    fun permitLeaseDuration(duration: Duration) {
        this.permitLeaseDuration = duration
    }

    fun permitRenewalInterval(interval: Duration) {
        this.permitRenewalInterval = interval
    }

    fun permitAcquireRetryDelay(delay: Duration) {
        this.permitAcquireRetryDelay = delay
    }

    fun permitRepository(repository: ScriptJobPermitRepository) {
        this.permitRepository = repository
    }

    fun engineConcurrency(engine: String, limit: Int) {
        engineConcurrency[engine] = limit
    }

    fun operatorConcurrency(operatorId: String, limit: Int) {
        operatorConcurrency[operatorId] = limit
    }

    fun targetTypeConcurrency(targetType: String, limit: Int) {
        targetTypeConcurrency[targetType] = limit
    }

    fun executionLimiter(limiter: ScriptJobExecutionLimiter) {
        this.executionLimiter = limiter
    }

    fun auditSink(sink: ScriptJobAuditSink) {
        this.auditSink = sink
    }

    internal fun build(): ScriptJobModuleOptions {
        require(recoveryLimit > 0) { "script job recovery limit must be positive" }
        require(recoveryTimeout > Duration.ZERO) { "script job recovery timeout must be positive" }
        recoveryScanInterval?.let { require(it > Duration.ZERO) { "script job recovery scan interval must be positive" } }
        require(claimBatchSize > 0) { "script job claim batch size must be positive" }
        require(leaseDuration > Duration.ZERO) { "script job lease duration must be positive" }
        require(leaseRenewalInterval > Duration.ZERO) { "script job lease renewal interval must be positive" }
        require(maxConcurrentItems > 0) { "script job max concurrent items must be positive" }
        require(permitPool.isNotBlank()) { "script job permit pool must not be blank" }
        require(permitLeaseDuration > Duration.ZERO) { "script job permit lease duration must be positive" }
        require(permitRenewalInterval > Duration.ZERO) { "script job permit renewal interval must be positive" }
        require(permitAcquireRetryDelay > Duration.ZERO) { "script job permit acquire retry delay must be positive" }
        engineConcurrency.forEach { (key, limit) ->
            require(key.isNotBlank()) { "script job engine concurrency key must not be blank" }
            require(limit > 0) { "script job engine concurrency limit must be positive" }
        }
        operatorConcurrency.forEach { (key, limit) ->
            require(key.isNotBlank()) { "script job operator concurrency key must not be blank" }
            require(limit > 0) { "script job operator concurrency limit must be positive" }
        }
        targetTypeConcurrency.forEach { (key, limit) ->
            require(key.isNotBlank()) { "script job target type concurrency key must not be blank" }
            require(limit > 0) { "script job target type concurrency limit must be positive" }
        }
        return ScriptJobModuleOptions(
            repository = repository,
            recoverOnStart = recoverOnStart,
            recoveryLimit = recoveryLimit,
            recoveryTimeout = recoveryTimeout,
            recoveryScanInterval = recoveryScanInterval,
            claimBatchSize = claimBatchSize,
            leaseDuration = leaseDuration,
            leaseRenewalInterval = leaseRenewalInterval,
            maxConcurrentItems = maxConcurrentItems,
            permitPool = permitPool,
            permitLeaseDuration = permitLeaseDuration,
            permitRenewalInterval = permitRenewalInterval,
            permitAcquireRetryDelay = permitAcquireRetryDelay,
            engineConcurrency = engineConcurrency.toMap(),
            operatorConcurrency = operatorConcurrency.toMap(),
            targetTypeConcurrency = targetTypeConcurrency.toMap(),
            permitRepository = permitRepository,
            executionLimiter = executionLimiter,
            auditSink = auditSink,
        )
    }
}
