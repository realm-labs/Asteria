package io.github.mikai233.asteria.id.zookeeper

import io.github.mikai233.asteria.id.WorkerId
import io.github.mikai233.asteria.id.WorkerIdOwner
import io.github.mikai233.asteria.id.WorkerIdRange
import io.github.mikai233.asteria.id.WorkerIdUnavailableException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.test.TestingServer
import org.apache.curator.x.async.AsyncCuratorFramework
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZookeeperWorkerIdRepositoryTest {
    @Test
    fun acquireAssignsDistinctWorkerIdsAndReusesOwnerLease() = runBlocking {
        withZookeeperRepository { repository ->
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
    }

    @Test
    fun expiredLeaseCanBeReacquired() = runBlocking {
        withZookeeperRepository { repository ->
            val range = WorkerIdRange.of(1, 1)
            val now = Instant.parse("2026-05-02T00:00:00Z")

            val old = repository.acquire(WorkerIdOwner("node-a"), range, Duration.ofSeconds(1), now)
            val next = repository.acquire(WorkerIdOwner("node-b"), range, Duration.ofSeconds(30), now.plusSeconds(2))

            assertEquals(old.id, next.id)
            assertNotEquals(old.token, next.token)
        }
    }

    @Test
    fun renewAndReleaseRequireMatchingLeaseToken() = runBlocking {
        withZookeeperRepository { repository ->
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
    }

    @Test
    fun unavailableRangeFailsFast() = runBlocking {
        withZookeeperRepository { repository ->
            repository.acquire(WorkerIdOwner("node-a"), WorkerIdRange.of(1, 1), Duration.ofSeconds(30))

            val failure = runCatching {
                repository.acquire(WorkerIdOwner("node-b"), WorkerIdRange.of(1, 1), Duration.ofSeconds(30))
            }

            assertIs<WorkerIdUnavailableException>(failure.exceptionOrNull())
        }
    }

    private suspend fun withZookeeperRepository(block: suspend (ZookeeperWorkerIdRepository) -> Unit) {
        withZookeeper { client ->
            block(ZookeeperWorkerIdRepository(AsyncCuratorFramework.wrap(client)))
        }
    }

    private suspend fun withZookeeper(block: suspend (CuratorFramework) -> Unit) {
        TestingServer().use { server ->
            val client = CuratorFrameworkFactory.newClient(
                server.connectString,
                ExponentialBackoffRetry(100, 3),
            )
            client.start()
            try {
                block(client)
            } finally {
                client.close()
            }
        }
    }
}
