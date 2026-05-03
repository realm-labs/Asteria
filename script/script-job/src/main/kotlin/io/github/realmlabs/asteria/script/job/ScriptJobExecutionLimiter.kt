package io.github.realmlabs.asteria.script.job

import io.github.realmlabs.asteria.script.ScriptExecutionCommand
import io.github.realmlabs.asteria.script.ScriptTarget
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class ScriptJobExecutionContext(
    val jobId: ScriptJobId,
    val itemId: ScriptJobItemId,
    val attempt: Int,
    val command: ScriptExecutionCommand,
    val workerId: String,
) {
    init {
        require(attempt > 0) { "script job execution attempt must be positive" }
        require(workerId.isNotBlank()) { "script job execution worker id must not be blank" }
    }
}

object ScriptJobExecutionAttributes {
    const val MaxConcurrentItems: String = "script.job.maxConcurrentItems"
}

/**
 * Controls how many script job items may execute at the same time.
 */
interface ScriptJobExecutionLimiter {
    suspend fun <T> limit(context: ScriptJobExecutionContext, block: suspend () -> T): T
}

object NoopScriptJobExecutionLimiter : ScriptJobExecutionLimiter {
    override suspend fun <T> limit(context: ScriptJobExecutionContext, block: suspend () -> T): T = block()
}

/**
 * Semaphore-based limiter with optional per-engine, per-operator, and per-target-type lanes.
 */
class SemaphoreScriptJobExecutionLimiter(
    private val globalLimit: Int = 256,
    engineLimits: Map<String, Int> = emptyMap(),
    operatorLimits: Map<String, Int> = emptyMap(),
    targetTypeLimits: Map<String, Int> = emptyMap(),
) : ScriptJobExecutionLimiter {
    private val global = semaphore(globalLimit, "global script job concurrency")
    private val engines = engineLimits.mapValues { (key, limit) -> semaphore(limit, "script engine $key concurrency") }
    private val operators =
        operatorLimits.mapValues { (key, limit) -> semaphore(limit, "script operator $key concurrency") }
    private val targetTypes = targetTypeLimits.mapKeys { it.key.lowercase() }
        .mapValues { (key, limit) -> semaphore(limit, "script target type $key concurrency") }
    private val jobs: MutableMap<String, Semaphore> = ConcurrentHashMap()

    override suspend fun <T> limit(context: ScriptJobExecutionContext, block: suspend () -> T): T {
        val permits = buildList {
            add(global)
            context.requestedMaxConcurrentItems()?.takeIf { it < globalLimit }?.let { limit ->
                add(jobs.computeIfAbsent(context.jobId.value) {
                    semaphore(
                        limit,
                        "script job ${context.jobId} concurrency"
                    )
                })
            }
            engines[context.command.artifact.engine]?.let { add(it) }
            context.command.metadata.requester?.let { operators[it]?.let(::add) }
            targetTypes[context.command.target.auditType()]?.let { add(it) }
        }
        return withPermits(permits, block)
    }

    private suspend fun <T> withPermits(
        permits: List<Semaphore>,
        block: suspend () -> T,
    ): T {
        suspend fun acquire(index: Int): T {
            if (index == permits.size) {
                return block()
            }
            return permits[index].withPermit { acquire(index + 1) }
        }
        return acquire(0)
    }

    private fun semaphore(limit: Int, label: String): Semaphore {
        require(limit > 0) { "$label limit must be positive" }
        return Semaphore(limit)
    }
}

/**
 * Distributed limiter backed by a shared permit repository.
 */
class RepositoryScriptJobExecutionLimiter(
    private val repository: ScriptJobPermitRepository,
    private val scope: CoroutineScope,
    private val pool: String = "script-job-items",
    private val maxConcurrentItems: Int = 256,
    private val permitsPerItem: Int = 1,
    private val leaseDuration: Duration = 30.seconds,
    private val renewalInterval: Duration = 10.seconds,
    private val retryDelay: Duration = 100.milliseconds,
) : ScriptJobExecutionLimiter {
    init {
        require(pool.isNotBlank()) { "script job permit pool must not be blank" }
        require(maxConcurrentItems > 0) { "script job max concurrent items must be positive" }
        require(permitsPerItem > 0) { "script job permits per item must be positive" }
        require(permitsPerItem <= maxConcurrentItems) { "script job permits per item must not exceed max concurrency" }
        require(leaseDuration > Duration.ZERO) { "script job permit lease duration must be positive" }
        require(renewalInterval > Duration.ZERO) { "script job permit renewal interval must be positive" }
        require(retryDelay > Duration.ZERO) { "script job permit retry delay must be positive" }
    }

    override suspend fun <T> limit(context: ScriptJobExecutionContext, block: suspend () -> T): T {
        val leases = acquire(context)
        val heartbeats = leases.map { startHeartbeat(it) }
        try {
            return block()
        } finally {
            heartbeats.forEach { it.cancel() }
            leases.forEach { repository.release(it) }
        }
    }

    private suspend fun acquire(context: ScriptJobExecutionContext): List<ScriptJobPermitLease> {
        while (true) {
            val now = System.currentTimeMillis()
            val globalLease = repository.acquire(
                pool = pool,
                owner = context.workerId,
                permits = permitsPerItem,
                limit = maxConcurrentItems,
                leaseUntilMillis = now + leaseDuration.inWholeMilliseconds,
                nowMillis = now,
            )
            if (globalLease == null) {
                delay(retryDelay)
                continue
            }
            val requestedLimit = context.requestedMaxConcurrentItems()
            if (requestedLimit == null || requestedLimit >= maxConcurrentItems) {
                return listOf(globalLease)
            }
            val jobLease = repository.acquire(
                pool = "$pool:job:${context.jobId.value}",
                owner = context.workerId,
                permits = permitsPerItem,
                limit = requestedLimit,
                leaseUntilMillis = now + leaseDuration.inWholeMilliseconds,
                nowMillis = now,
            )
            if (jobLease != null) {
                return listOf(globalLease, jobLease)
            }
            repository.release(globalLease)
            delay(retryDelay)
        }
    }

    private fun startHeartbeat(lease: ScriptJobPermitLease): Job {
        return scope.launch {
            while (isActive) {
                delay(renewalInterval)
                val now = System.currentTimeMillis()
                val renewed = repository.renew(
                    lease = lease,
                    leaseUntilMillis = now + leaseDuration.inWholeMilliseconds,
                    nowMillis = now,
                )
                if (!renewed) {
                    return@launch
                }
            }
        }
    }
}

private fun ScriptJobExecutionContext.requestedMaxConcurrentItems(): Int? {
    val value = command.metadata.attributes[ScriptJobExecutionAttributes.MaxConcurrentItems] ?: return null
    val parsed = value.toIntOrNull()
    require(parsed != null && parsed > 0) { "script job max concurrent items must be positive" }
    return parsed
}

internal fun ScriptTarget.auditType(): String {
    return when (this) {
        ScriptTarget.AllNodes -> "all-nodes"
        is ScriptTarget.ActorPath -> "actor-paths"
        is ScriptTarget.Entity -> "entity"
        is ScriptTarget.Node -> "nodes"
        is ScriptTarget.Role -> "role"
        is ScriptTarget.Singleton -> "singleton"
    }
}
