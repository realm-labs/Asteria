package io.github.realmlabs.asteria.script.job

import io.github.realmlabs.asteria.script.ScriptExecutionCommand
import io.github.realmlabs.asteria.script.policyType
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
    const val MAX_CONCURRENT_ITEMS: String = "script.job.maxConcurrentItems"
}

/**
 * Wraps script item execution with a concurrency policy.
 *
 * Implementations must run [block] exactly once after capacity is acquired. If the policy can no longer prove that
 * capacity is still owned while [block] is running, it should cancel or fail the call rather than allowing execution to
 * continue outside the agreed limit.
 */
interface ScriptJobExecutionLimiter {
    suspend fun <T> limit(context: ScriptJobExecutionContext, block: suspend () -> T): T
}

/**
 * Explicit opt-out limiter for tests or deployments that own concurrency elsewhere.
 */
object NoopScriptJobExecutionLimiter : ScriptJobExecutionLimiter {
    override suspend fun <T> limit(context: ScriptJobExecutionContext, block: suspend () -> T): T = block()
}

/**
 * In-process limiter with optional per-engine, per-operator, and per-target-type lanes.
 *
 * This limiter only coordinates work inside the current process. Multi-node deployments that need a cluster-wide cap
 * should use [RepositoryScriptJobExecutionLimiter].
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
            targetTypes[context.command.target.policyType()]?.let { add(it) }
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
 * Distributed limiter backed by [ScriptJobPermitRepository] leases.
 *
 * Acquiring capacity retries on repository exceptions and on full pools until the caller is cancelled. Once [block]
 * starts, permit renewals run as child coroutines. A failed renewal is retried until the current lease expires; a
 * confirmed lost or expired lease fails the surrounding [limit] call with [ScriptJobPermitLeaseLostException], which
 * cancels the running item through structured concurrency.
 */
class RepositoryScriptJobExecutionLimiter(
    private val repository: ScriptJobPermitRepository,
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
        return coroutineScope {
            val heartbeats = leases.map { startHeartbeat(this, it) }
            try {
                block()
            } finally {
                heartbeats.forEach { it.cancelAndJoin() }
                leases.forEach { lease ->
                    runCatching { repository.release(lease) }
                }
            }
        }
    }

    private suspend fun acquire(context: ScriptJobExecutionContext): List<ScriptJobPermitLease> {
        while (true) {
            val now = System.currentTimeMillis()
            val globalLease = try {
                repository.acquire(
                    pool = pool,
                    owner = context.workerId,
                    permits = permitsPerItem,
                    limit = maxConcurrentItems,
                    leaseUntilMillis = now + leaseDuration.inWholeMilliseconds,
                    nowMillis = now,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                delay(retryDelay)
                continue
            }
            if (globalLease == null) {
                delay(retryDelay)
                continue
            }
            val requestedLimit = context.requestedMaxConcurrentItems()
            if (requestedLimit == null || requestedLimit >= maxConcurrentItems) {
                return listOf(globalLease)
            }
            val jobLease = try {
                repository.acquire(
                    pool = "$pool:job:${context.jobId.value}",
                    owner = context.workerId,
                    permits = permitsPerItem,
                    limit = requestedLimit,
                    leaseUntilMillis = now + leaseDuration.inWholeMilliseconds,
                    nowMillis = now,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                runCatching { repository.release(globalLease) }
                delay(retryDelay)
                continue
            }
            if (jobLease != null) {
                return listOf(globalLease, jobLease)
            }
            runCatching { repository.release(globalLease) }
            delay(retryDelay)
        }
    }

    private fun startHeartbeat(
        scope: CoroutineScope,
        lease: ScriptJobPermitLease,
    ): Job {
        return scope.launch {
            var leaseUntilMillis = lease.leaseUntilMillis
            while (isActive) {
                delay(renewalInterval)
                val now = System.currentTimeMillis()
                leaseUntilMillis = renewPermitLease(lease, leaseUntilMillis, now)
            }
        }
    }

    private suspend fun renewPermitLease(
        lease: ScriptJobPermitLease,
        currentLeaseUntilMillis: Long,
        startedAtMillis: Long = System.currentTimeMillis(),
    ): Long {
        var now = startedAtMillis
        val leaseUntilMillis = currentLeaseUntilMillis
        while (currentCoroutineContext().isActive) {
            if (now >= leaseUntilMillis) {
                throw ScriptJobPermitLeaseLostException(lease)
            }
            val nextLeaseUntilMillis = now + leaseDuration.inWholeMilliseconds
            try {
                val renewed = repository.renew(
                    lease = lease,
                    leaseUntilMillis = nextLeaseUntilMillis,
                    nowMillis = now,
                )
                if (!renewed) {
                    throw ScriptJobPermitLeaseLostException(lease)
                }
                return nextLeaseUntilMillis
            } catch (error: CancellationException) {
                throw error
            } catch (error: ScriptJobPermitLeaseLostException) {
                throw error
            } catch (error: Throwable) {
                now = System.currentTimeMillis()
                if (now >= leaseUntilMillis) {
                    throw ScriptJobPermitLeaseLostException(lease, error)
                }
                delay(minOf(retryDelay.inWholeMilliseconds, leaseUntilMillis - now).coerceAtLeast(1).milliseconds)
                now = System.currentTimeMillis()
            }
        }
        throw CancellationException("script job permit heartbeat was cancelled")
    }
}

/**
 * Raised when a running script job no longer owns its distributed permit lease.
 */
class ScriptJobPermitLeaseLostException(
    val lease: ScriptJobPermitLease,
    cause: Throwable? = null,
) : IllegalStateException("script job permit lease ${lease.id} for ${lease.owner} is lost", cause)

private fun ScriptJobExecutionContext.requestedMaxConcurrentItems(): Int? {
    val value = command.metadata.attributes[ScriptJobExecutionAttributes.MAX_CONCURRENT_ITEMS] ?: return null
    val parsed = value.toIntOrNull()
    require(parsed != null && parsed > 0) { "script job max concurrent items must be positive" }
    return parsed
}
