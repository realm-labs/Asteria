package io.github.realmlabs.asteria.script.job

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*

@JvmInline
value class ScriptJobPermitLeaseId(val value: String) {
    init {
        require(value.isNotBlank()) { "script job permit lease id must not be blank" }
    }

    override fun toString(): String = value
}

data class ScriptJobPermitLease(
    val id: ScriptJobPermitLeaseId,
    val pool: String,
    val owner: String,
    val permits: Int,
    val leaseUntilMillis: Long,
    val acquiredAtMillis: Long = System.currentTimeMillis(),
) {
    init {
        require(pool.isNotBlank()) { "script job permit pool must not be blank" }
        require(owner.isNotBlank()) { "script job permit owner must not be blank" }
        require(permits > 0) { "script job permit count must be positive" }
        require(leaseUntilMillis > acquiredAtMillis) { "script job permit lease must be in the future" }
    }
}

/**
 * Shared permit store used by [RepositoryScriptJobExecutionLimiter].
 *
 * A permit lease reserves capacity in one named [pool]. Repository methods return `null` or `false` when capacity or
 * ownership is not available; transient backend outages should be reported by throwing so the limiter can retry until
 * the active lease window closes.
 */
interface ScriptJobPermitRepository {
    /**
     * Attempts to reserve [permits] in [pool] without exceeding [limit].
     *
     * Returns `null` when the pool is currently full. Implementations should ignore or remove expired leases before
     * checking capacity.
     */
    suspend fun acquire(
        pool: String,
        owner: String,
        permits: Int,
        limit: Int,
        leaseUntilMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): ScriptJobPermitLease?

    /**
     * Extends [lease].
     *
     * Returns `false` when the lease has expired or no longer belongs to [ScriptJobPermitLease.owner]. The running job
     * item must then stop because another worker may acquire the same capacity.
     */
    suspend fun renew(
        lease: ScriptJobPermitLease,
        leaseUntilMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean

    /**
     * Releases [lease].
     *
     * A `false` result is not fatal for callers; it usually means the lease already expired or was cleaned up.
     */
    suspend fun release(lease: ScriptJobPermitLease): Boolean
}

/**
 * Single-process permit repository for local development and tests.
 */
class InMemoryScriptJobPermitRepository : ScriptJobPermitRepository {
    private val mutex = Mutex()
    private val leases: MutableMap<ScriptJobPermitLeaseId, ScriptJobPermitLease> = linkedMapOf()

    override suspend fun acquire(
        pool: String,
        owner: String,
        permits: Int,
        limit: Int,
        leaseUntilMillis: Long,
        nowMillis: Long,
    ): ScriptJobPermitLease? {
        require(pool.isNotBlank()) { "script job permit pool must not be blank" }
        require(owner.isNotBlank()) { "script job permit owner must not be blank" }
        require(permits > 0) { "script job permit count must be positive" }
        require(limit > 0) { "script job permit limit must be positive" }
        require(permits <= limit) { "script job permit count must not exceed limit" }
        require(leaseUntilMillis > nowMillis) { "script job permit lease must be in the future" }
        return mutex.withLock {
            expire(nowMillis)
            val active = leases.values
                .asSequence()
                .filter { it.pool == pool }
                .sumOf { it.permits }
            if (active + permits > limit) {
                return@withLock null
            }
            val lease = ScriptJobPermitLease(
                id = ScriptJobPermitLeaseId(UUID.randomUUID().toString()),
                pool = pool,
                owner = owner,
                permits = permits,
                leaseUntilMillis = leaseUntilMillis,
                acquiredAtMillis = nowMillis,
            )
            leases[lease.id] = lease
            lease
        }
    }

    override suspend fun renew(
        lease: ScriptJobPermitLease,
        leaseUntilMillis: Long,
        nowMillis: Long,
    ): Boolean {
        require(leaseUntilMillis > nowMillis) { "script job permit lease must be in the future" }
        return mutex.withLock {
            expire(nowMillis)
            val current = leases[lease.id] ?: return@withLock false
            if (current.owner != lease.owner || current.pool != lease.pool) {
                return@withLock false
            }
            leases[lease.id] = current.copy(leaseUntilMillis = leaseUntilMillis)
            true
        }
    }

    override suspend fun release(lease: ScriptJobPermitLease): Boolean {
        return mutex.withLock {
            leases.remove(lease.id) != null
        }
    }

    private fun expire(nowMillis: Long) {
        leases.values.removeIf { it.leaseUntilMillis <= nowMillis }
    }
}
