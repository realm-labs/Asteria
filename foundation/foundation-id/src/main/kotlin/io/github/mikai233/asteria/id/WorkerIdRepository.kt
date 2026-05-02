package io.github.mikai233.asteria.id

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface WorkerIdRepository {
    suspend fun acquire(
        owner: WorkerIdOwner,
        range: WorkerIdRange,
        ttl: Duration,
        now: Instant = Instant.now(),
    ): WorkerIdLease

    suspend fun renew(
        lease: WorkerIdLease,
        ttl: Duration,
        now: Instant = Instant.now(),
    ): WorkerIdLease?

    suspend fun release(lease: WorkerIdLease): Boolean

    suspend fun leases(now: Instant = Instant.now()): List<WorkerIdLease>
}

class WorkerIdUnavailableException(
    owner: WorkerIdOwner,
    range: WorkerIdRange,
) : IllegalStateException("no worker id available for owner $owner in range ${range.start.value}..${range.endInclusive.value}")

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
