package io.github.realmlabs.asteria.id

import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import kotlin.test.*

class WorkerIdRepositoryTest {
    @Test
    fun acquireAssignsDistinctWorkerIdsAndReusesOwnerLease() = runBlocking {
        val repository = InMemoryWorkerIdRepository()
        val range = WorkerIdRange.of(1, 2)
        val now = Instant.parse("2026-05-02T00:00:00Z")

        val first = repository.acquire(WorkerIdOwner("node-a"), range, Duration.ofSeconds(30), now)
        val firstAgain = repository.acquire(WorkerIdOwner("node-a"), range, Duration.ofSeconds(30), now.plusSeconds(1))
        val second = repository.acquire(WorkerIdOwner("node-b"), range, Duration.ofSeconds(30), now)

        assertEquals(WorkerId(1), first.id)
        assertEquals(first.id, firstAgain.id)
        assertEquals(first.token, firstAgain.token)
        assertEquals(WorkerId(2), second.id)
    }

    @Test
    fun expiredLeaseCanBeReacquired() = runBlocking {
        val repository = InMemoryWorkerIdRepository()
        val range = WorkerIdRange.of(1, 1)
        val now = Instant.parse("2026-05-02T00:00:00Z")

        val old = repository.acquire(WorkerIdOwner("node-a"), range, Duration.ofSeconds(1), now)
        val next = repository.acquire(WorkerIdOwner("node-b"), range, Duration.ofSeconds(30), now.plusSeconds(2))

        assertEquals(old.id, next.id)
        assertNotEquals(old.token, next.token)
    }

    @Test
    fun renewAndReleaseRequireMatchingLeaseToken() = runBlocking {
        val repository = InMemoryWorkerIdRepository()
        val now = Instant.parse("2026-05-02T00:00:00Z")
        val lease = repository.acquire(
            WorkerIdOwner("node-a"),
            WorkerIdRange.of(1, 1),
            Duration.ofSeconds(30),
            now,
        )
        val stale = lease.copy(token = "stale")

        assertNull(repository.renew(stale, Duration.ofSeconds(30), now.plusSeconds(1)))
        assertFalse(repository.release(stale))
        assertTrue(repository.release(lease))
        assertEquals(emptyList(), repository.leases())
    }

    @Test
    fun unavailableRangeFailsFast() = runBlocking {
        val repository = InMemoryWorkerIdRepository()
        repository.acquire(WorkerIdOwner("node-a"), WorkerIdRange.of(1, 1), Duration.ofSeconds(30))

        val failure = runCatching {
            repository.acquire(WorkerIdOwner("node-b"), WorkerIdRange.of(1, 1), Duration.ofSeconds(30))
        }

        assertIs<WorkerIdUnavailableException>(failure.exceptionOrNull())
    }
}
