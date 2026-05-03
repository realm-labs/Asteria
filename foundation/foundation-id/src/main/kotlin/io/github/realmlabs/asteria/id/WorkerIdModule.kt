package io.github.realmlabs.asteria.id

import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext
import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.metricsOrNoop
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.*
import java.util.concurrent.atomic.AtomicReference

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
        val generator = options.generator(acquired.id)
        val workerIdRuntime = WorkerIdRuntime(acquired, generator)
        lease = acquired
        runtime = workerIdRuntime
        context.services.register(WorkerIdRuntime::class, workerIdRuntime)
        context.services.register(IdGenerator::class, generator)
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob()).also { scope ->
            renewJob = scope.launch {
                var current = acquired
                while (isActive) {
                    delay(options.renewInterval.toMillis())
                    val renewed = repository.renew(current, options.ttl)
                    if (renewed == null) {
                        metrics.counter("asteria.worker_id.renew.failed.total", current.metricTags(context.name))
                            .increment()
                        logger.error(
                            "worker id lease lost app={} id={} owner={}",
                            context.name,
                            current.id.value,
                            current.owner.value,
                        )
                        error("worker id lease ${current.id} for ${current.owner} was lost")
                    }
                    metrics.counter("asteria.worker_id.renewed.total", renewed.metricTags(context.name)).increment()
                    current = renewed
                    lease = renewed
                    workerIdRuntime.update(renewed)
                }
            }
        }
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
 * The lease can change after periodic renewal. Keep this object if diagnostics need the current
 * lease; use the registered [IdGenerator] when only ID generation is needed.
 */
class WorkerIdRuntime internal constructor(
    initialLease: WorkerIdLease,
    val idGenerator: IdGenerator,
) {
    private val leaseRef = AtomicReference(initialLease)

    val lease: WorkerIdLease get() = leaseRef.get()
    val id: WorkerId get() = lease.id

    internal fun update(lease: WorkerIdLease) {
        leaseRef.set(lease)
    }
}

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
