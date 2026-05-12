package io.github.realmlabs.asteria.id.zookeeper

import io.github.realmlabs.asteria.id.WorkerId
import io.github.realmlabs.asteria.id.WorkerIdOwner
import io.github.realmlabs.asteria.id.WorkerIdRange
import io.github.realmlabs.asteria.id.WorkerIdUnavailableException
import kotlinx.coroutines.runBlocking
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.test.TestingServer
import org.apache.curator.x.async.AsyncCuratorFramework
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ZookeeperWorkerIdRepositoryTest {
    @Test
    fun acquireAssignsDistinctWorkerIdsAndReusesOwnerLease(): Unit = runBlocking {
        withZookeeperRepository { repository ->
            val range = WorkerIdRange.of(1, 2)
            val now = Instant.parse("2026-05-02T00:00:00Z")

            val first = repository.acquire(WorkerIdOwner("node-a"), range, 30.seconds, now)
            val firstAgain =
                repository.acquire(WorkerIdOwner("node-a"), range, 30.seconds, now + 1.seconds)
            val second = repository.acquire(WorkerIdOwner("node-b"), range, 30.seconds, now)

            assertEquals(WorkerId(1), first.id)
            assertEquals(first.id, firstAgain.id)
            assertEquals(first.token, firstAgain.token)
            assertEquals(WorkerId(2), second.id)
        }
    }

    @Test
    fun expiredLeaseCanBeReacquired(): Unit = runBlocking {
        withZookeeperRepository { repository ->
            val range = WorkerIdRange.of(1, 1)
            val now = Instant.parse("2026-05-02T00:00:00Z")

            val old = repository.acquire(WorkerIdOwner("node-a"), range, 1.seconds, now)
            val next = repository.acquire(WorkerIdOwner("node-b"), range, 30.seconds, now + 2.seconds)

            assertEquals(old.id, next.id)
            assertNotEquals(old.token, next.token)
        }
    }

    @Test
    fun renewAndReleaseRequireMatchingLeaseToken(): Unit = runBlocking {
        withZookeeperRepository { repository ->
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
    }

    @Test
    fun unavailableRangeFailsFast(): Unit = runBlocking {
        withZookeeperRepository { repository ->
            repository.acquire(WorkerIdOwner("node-a"), WorkerIdRange.of(1, 1), 30.seconds)

            val failure = runCatching {
                repository.acquire(WorkerIdOwner("node-b"), WorkerIdRange.of(1, 1), 30.seconds)
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
            client.use { client ->
                block(client)
            }
        }
    }
}
