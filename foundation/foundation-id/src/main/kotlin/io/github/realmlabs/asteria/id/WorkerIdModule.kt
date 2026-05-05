package io.github.realmlabs.asteria.id

import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext
import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.metricsOrNoop
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

/**
 * Acquires a process-wide [WorkerId] and registers a lease-aware [IdGenerator].
 *
 * The module renews the lease in the background. Transient repository failures are retried until the current lease
 * expires. Once renewal returns `null` or the lease expires before a successful retry, the registered generator fails
 * closed with [WorkerIdLeaseLostException] so the process cannot keep issuing IDs for an id it may no longer own.
 */
class WorkerIdModule(
    private val repository: WorkerIdRepository,
    private val options: WorkerIdModuleOptions = WorkerIdModuleOptions(),
) : AsteriaModule {
    override val name: String = "worker-id"

    private val logger = LoggerFactory.getLogger(WorkerIdModule::class.java)
    private val defaultOwner = WorkerIdOwner("worker-${UUID.randomUUID()}")
    private var lease: WorkerIdLease? = null
    private var runtime: WorkerIdRuntime? = null
    private var scope: CoroutineScope? = null
    private var renewJob: Job? = null

    override suspend fun start(context: ModuleContext) {
        val metrics = context.metricsOrNoop()
        val owner = options.owner?.invoke(context) ?: defaultOwner
        val acquired = repository.acquire(owner, options.range, options.ttl)
        val tags = acquired.metricTags(context.name)
        metrics.counter("asteria.worker_id.acquired.total", tags).increment()
        logger.info(
            "worker id acquired app={} id={} owner={} expiresAt={}",
            context.name,
            acquired.id.value,
            acquired.owner.value,
            acquired.expiresAt,
        )
        val workerIdRuntime = WorkerIdRuntime(acquired, options.generator(acquired.id))
        lease = acquired
        runtime = workerIdRuntime
        context.services.register(WorkerIdRuntime::class, workerIdRuntime)
        context.services.register(IdGenerator::class, workerIdRuntime.idGenerator)
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob()).also { scope ->
            renewJob = scope.launch {
                var current = acquired
                while (isActive) {
                    delay(options.renewInterval.toMillis().milliseconds)
                    val renewed = renewLease(context.name, current, workerIdRuntime, metrics) ?: return@launch
                    metrics.counter("asteria.worker_id.renewed.total", renewed.metricTags(context.name)).increment()
                    current = renewed
                    lease = renewed
                    workerIdRuntime.update(renewed)
                }
            }
        }
    }

    private suspend fun renewLease(
        appName: String,
        current: WorkerIdLease,
        runtime: WorkerIdRuntime,
        metrics: Metrics,
    ): WorkerIdLease? {
        while (currentCoroutineContext().isActive) {
            try {
                val renewed = repository.renew(current, options.ttl)
                if (renewed != null) {
                    return renewed
                }
                metrics.counter("asteria.worker_id.renew.failed.total", current.metricTags(appName)).increment()
                markLeaseLost(appName, current, runtime, null)
                return null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val now = Instant.now()
                if (!current.expiresAt.isAfter(now)) {
                    metrics.counter("asteria.worker_id.renew.failed.total", current.metricTags(appName)).increment()
                    markLeaseLost(appName, current, runtime, error)
                    return null
                }
                metrics.counter("asteria.worker_id.renew.failed.total", current.metricTags(appName)).increment()
                logger.warn(
                    "worker id renew failed app={} id={} owner={} expiresAt={}; retrying",
                    appName,
                    current.id.value,
                    current.owner.value,
                    current.expiresAt,
                    error,
                )
                delay(retryDelayMillis(now, current).milliseconds)
            }
        }
        return null
    }

    private fun markLeaseLost(
        appName: String,
        current: WorkerIdLease,
        runtime: WorkerIdRuntime,
        cause: Throwable?,
    ) {
        runtime.markLost(cause)
        logger.error(
            "worker id lease lost app={} id={} owner={} expiresAt={}",
            appName,
            current.id.value,
            current.owner.value,
            current.expiresAt,
            cause,
        )
    }

    private fun retryDelayMillis(
        now: Instant,
        current: WorkerIdLease,
    ): Long {
        val untilExpiry = Duration.between(now, current.expiresAt).toMillis()
        return minOf(
            DEFAULT_RENEW_RETRY_DELAY.toMillis(),
            options.renewInterval.toMillis(),
            untilExpiry,
        ).coerceAtLeast(1)
    }

    override suspend fun stop(context: ModuleContext) {
        renewJob?.cancelAndJoin()
        renewJob = null
        scope?.cancel()
        scope = null
        lease?.let {
            val released = repository.release(it)
            context.metricsOrNoop().counter(
                "asteria.worker_id.released.total",
                it.metricTags(context.name) + MetricTags.of("released" to released.toString()),
            ).increment()
            logger.info(
                "worker id released app={} id={} owner={} released={}",
                context.name,
                it.id.value,
                it.owner.value,
                released,
            )
        }
        lease = null
        runtime = null
    }
}

private fun WorkerIdLease.metricTags(appName: String): MetricTags {
    return MetricTags.of(
        "app" to appName,
        "worker_id" to id.value.toString(),
    )
}

/**
 * Runtime view for the worker id owned by this process.
 *
 * [lease] is updated after successful renewals. [lost] becomes true when the module can no longer prove ownership; the
 * registered [idGenerator] then throws [WorkerIdLeaseLostException] instead of producing IDs.
 */
class WorkerIdRuntime internal constructor(
    initialLease: WorkerIdLease,
    initialGenerator: IdGenerator,
) {
    private val stateRef = AtomicReference<WorkerIdRuntimeState>(
        WorkerIdRuntimeState.Active(initialLease, initialGenerator),
    )

    val lease: WorkerIdLease get() = stateRef.get().lease
    val id: WorkerId get() = lease.id
    val lost: Boolean get() = stateRef.get() is WorkerIdRuntimeState.Lost
    val idGenerator: IdGenerator = LeaseAwareIdGenerator(stateRef)

    internal fun update(lease: WorkerIdLease) {
        while (true) {
            val current = stateRef.get()
            val active = current as? WorkerIdRuntimeState.Active ?: return
            if (stateRef.compareAndSet(current, active.copy(lease = lease))) {
                return
            }
        }
    }

    internal fun markLost(cause: Throwable?) {
        val current = stateRef.get()
        stateRef.set(WorkerIdRuntimeState.Lost(current.lease, cause))
    }
}

/**
 * Raised by the registered [IdGenerator] after worker-id ownership is lost.
 */
class WorkerIdLeaseLostException(
    val lease: WorkerIdLease,
    cause: Throwable? = null,
) : IllegalStateException("worker id lease ${lease.id} for ${lease.owner} is lost", cause)

private sealed interface WorkerIdRuntimeState {
    val lease: WorkerIdLease

    data class Active(
        override val lease: WorkerIdLease,
        val generator: IdGenerator,
    ) : WorkerIdRuntimeState

    data class Lost(
        override val lease: WorkerIdLease,
        val cause: Throwable?,
    ) : WorkerIdRuntimeState
}

private class LeaseAwareIdGenerator(
    private val stateRef: AtomicReference<WorkerIdRuntimeState>,
) : IdGenerator {
    override fun nextId(): Long {
        return when (val state = stateRef.get()) {
            is WorkerIdRuntimeState.Active -> state.generator.nextId()
            is WorkerIdRuntimeState.Lost -> throw WorkerIdLeaseLostException(state.lease, state.cause)
        }
    }
}

private val DEFAULT_RENEW_RETRY_DELAY: Duration = Duration.ofSeconds(1)

data class WorkerIdModuleOptions(
    val range: WorkerIdRange = WorkerIdRange.of(0, 1023),
    val ttl: Duration = Duration.ofSeconds(30),
    val renewInterval: Duration = Duration.ofSeconds(10),
    val owner: ((ModuleContext) -> WorkerIdOwner)? = null,
    val generator: (WorkerId) -> IdGenerator = { workerId -> SnowflakeIdGenerator(workerId) },
) {
    init {
        require(!ttl.isNegative && !ttl.isZero) { "worker id ttl must be positive" }
        require(!renewInterval.isNegative && !renewInterval.isZero) { "worker id renewInterval must be positive" }
        require(renewInterval < ttl) { "worker id renewInterval must be smaller than ttl" }
    }
}
