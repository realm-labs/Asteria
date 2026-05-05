package io.github.realmlabs.asteria.id

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * Shared lease store for process-local worker ids.
 *
 * A repository implementation must treat the lease token as the ownership proof. Renewing or releasing a lease with a
 * stale token must fail rather than touching a newer lease for the same numeric worker id. Transient backend errors
 * should be reported by throwing; [WorkerIdModule] retries renewals until the currently held lease expires.
 */
interface WorkerIdRepository {
    /**
     * Acquires an id in [range] for [owner].
     *
     * Implementations may return an existing unexpired lease for the same owner if it is still inside [range].
     * [WorkerIdUnavailableException] means no id is available after expired leases have been ignored or removed.
     */
    suspend fun acquire(
        owner: WorkerIdOwner,
        range: WorkerIdRange,
        ttl: Duration,
        now: Instant = Instant.now(),
    ): WorkerIdLease

    /**
     * Extends [lease] and returns the refreshed lease when ownership is still valid.
     *
     * `null` means the lease is no longer owned by the caller: it expired, was released, or was replaced by another
     * owner/token. Callers must stop using the associated worker id after `null`.
     */
    suspend fun renew(
        lease: WorkerIdLease,
        ttl: Duration,
        now: Instant = Instant.now(),
    ): WorkerIdLease?

    /**
     * Releases [lease] only when its token still matches the stored owner.
     *
     * Returns `false` when the lease is already gone or no longer belongs to the caller.
     */
    suspend fun release(lease: WorkerIdLease): Boolean

    /**
     * Returns currently active leases after ignoring or removing expired entries.
     */
    suspend fun leases(now: Instant = Instant.now()): List<WorkerIdLease>
}

class WorkerIdUnavailableException(
    owner: WorkerIdOwner,
    range: WorkerIdRange,
) : IllegalStateException("no worker id available for owner $owner in range ${range.start.value}..${range.endInclusive.value}")

/**
 * Single-process implementation for tests and local development.
 */
class InMemoryWorkerIdRepository : WorkerIdRepository {
    private val lock = Mutex()
    private val leasesById: MutableMap<WorkerId, WorkerIdLease> = linkedMapOf()

    override suspend fun acquire(
        owner: WorkerIdOwner,
        range: WorkerIdRange,
        ttl: Duration,
        now: Instant,
    ): WorkerIdLease {
        require(!ttl.isNegative && !ttl.isZero) { "worker id lease ttl must be positive" }
        return lock.withLock {
            removeExpired(now)
            val current = leasesById.values.firstOrNull { lease -> lease.owner == owner && lease.id in range }
            if (current != null) {
                val renewed = current.copy(expiresAt = now.plus(ttl))
                leasesById[current.id] = renewed
                return@withLock renewed
            }
            val free = range.ids().map(::WorkerId).firstOrNull { id -> id !in leasesById }
                ?: throw WorkerIdUnavailableException(owner, range)
            val lease = WorkerIdLease(
                id = free,
                owner = owner,
                token = UUID.randomUUID().toString(),
                acquiredAt = now,
                expiresAt = now.plus(ttl),
            )
            leasesById[free] = lease
            lease
        }
    }

    override suspend fun renew(
        lease: WorkerIdLease,
        ttl: Duration,
        now: Instant,
    ): WorkerIdLease? {
        require(!ttl.isNegative && !ttl.isZero) { "worker id lease ttl must be positive" }
        return lock.withLock {
            val current = leasesById[lease.id] ?: return@withLock null
            if (current.token != lease.token || current.owner != lease.owner || current.isExpired(now)) {
                if (current.isExpired(now)) {
                    leasesById.remove(current.id)
                }
                return@withLock null
            }
            val renewed = current.copy(expiresAt = now.plus(ttl))
            leasesById[current.id] = renewed
            renewed
        }
    }

    override suspend fun release(lease: WorkerIdLease): Boolean {
        return lock.withLock {
            val current = leasesById[lease.id] ?: return@withLock false
            if (current.token != lease.token || current.owner != lease.owner) {
                return@withLock false
            }
            leasesById.remove(lease.id)
            true
        }
    }

    override suspend fun leases(now: Instant): List<WorkerIdLease> {
        return lock.withLock {
            removeExpired(now)
            leasesById.values.sortedBy { it.id.value }
        }
    }

    private fun removeExpired(now: Instant) {
        leasesById.values.removeIf { lease -> lease.isExpired(now) }
    }
}
