package io.github.mikai233.asteria.id

import io.github.mikai233.asteria.core.AsteriaModule
import io.github.mikai233.asteria.core.ModuleContext
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WorkerIdModule(
    private val repository: WorkerIdRepository,
    private val options: WorkerIdModuleOptions = WorkerIdModuleOptions(),
) : AsteriaModule {
    override val name: String = "worker-id"

    private val defaultOwner = WorkerIdOwner("worker-${UUID.randomUUID()}")
    private var lease: WorkerIdLease? = null
    private var runtime: WorkerIdRuntime? = null
    private var scope: CoroutineScope? = null
    private var renewJob: Job? = null

    override suspend fun start(context: ModuleContext) {
        val owner = options.owner?.invoke(context) ?: defaultOwner
        val acquired = repository.acquire(owner, options.range, options.ttl)
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
                        ?: error("worker id lease ${current.id} for ${current.owner} was lost")
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
        lease?.let { repository.release(it) }
        lease = null
        runtime = null
    }
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
