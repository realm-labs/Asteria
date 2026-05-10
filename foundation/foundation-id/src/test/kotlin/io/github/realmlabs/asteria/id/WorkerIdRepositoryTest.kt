package io.github.realmlabs.asteria.id

import kotlinx.coroutines.runBlocking
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class WorkerIdRepositoryTest {
    @Test
    fun acquireAssignsDistinctWorkerIdsAndReusesOwnerLease(): Unit = runBlocking {
        val repository = InMemoryWorkerIdRepository()
        val range = WorkerIdRange.of(1, 2)
        val now = Instant.parse("2026-05-02T00:00:00Z")

        val first = repository.acquire(WorkerIdOwner("node-a"), range, 30.seconds, now)
        val firstAgain = repository.acquire(WorkerIdOwner("node-a"), range, 30.seconds, now + 1.seconds)
        val second = repository.acquire(WorkerIdOwner("node-b"), range, 30.seconds, now)

        assertEquals(WorkerId(1), first.id)
        assertEquals(first.id, firstAgain.id)
        assertEquals(first.token, firstAgain.token)
        assertEquals(WorkerId(2), second.id)
    }

    @Test
    fun expiredLeaseCanBeReacquired(): Unit = runBlocking {
        val repository = InMemoryWorkerIdRepository()
        val range = WorkerIdRange.of(1, 1)
        val now = Instant.parse("2026-05-02T00:00:00Z")

        val old = repository.acquire(WorkerIdOwner("node-a"), range, 1.seconds, now)
        val next = repository.acquire(WorkerIdOwner("node-b"), range, 30.seconds, now + 2.seconds)

        assertEquals(old.id, next.id)
        assertNotEquals(old.token, next.token)
    }

    @Test
    fun renewAndReleaseRequireMatchingLeaseToken(): Unit = runBlocking {
        val repository = InMemoryWorkerIdRepository()
        val now = Instant.parse("2026-05-02T00:00:00Z")
        val lease = repository.acquire(
            WorkerIdOwner("node-a"),
            WorkerIdRange.of(1, 1),
            30.seconds,
            now,
        )
        val stale = lease.copy(token = "stale")

        assertNull(repository.renew(stale, 30.seconds, now + 1.seconds))
        assertFalse(repository.release(stale))
        assertTrue(repository.release(lease))
        assertEquals(emptyList(), repository.leases())
    }

    @Test
    fun unavailableRangeFailsFast(): Unit = runBlocking {
        val repository = InMemoryWorkerIdRepository()
        repository.acquire(WorkerIdOwner("node-a"), WorkerIdRange.of(1, 1), 30.seconds)

        val failure = runCatching {
            repository.acquire(WorkerIdOwner("node-b"), WorkerIdRange.of(1, 1), 30.seconds)
        }

        assertIs<WorkerIdUnavailableException>(failure.exceptionOrNull())
    }
}
