package io.github.realmlabs.asteria.id

import io.github.realmlabs.asteria.core.gameApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkerIdModuleTest {
    @Test
    fun moduleAcquiresWorkerIdAndRegistersRuntimeServices() = runBlocking {
        val repository = InMemoryWorkerIdRepository()
        val app = gameApplication {
            name = "test-game"
            install(
                WorkerIdModule(
                    repository,
                    WorkerIdModuleOptions(
                        range = WorkerIdRange.of(7, 7),
                        owner = { WorkerIdOwner("node-a") },
                    ),
                ),
            )
        }

        app.launch()

        val runtime = app.services.get<WorkerIdRuntime>()
        val generator = app.services.get<IdGenerator>()

        assertEquals(WorkerId(7), runtime.id)
        assertEquals(generator, runtime.idGenerator)
        assertNotNull(generator.nextId())

        app.stop()

        assertEquals(emptyList(), repository.leases())
    }

    @Test
    fun defaultOwnerIsUniquePerModuleInstance() = runBlocking {
        val repository = InMemoryWorkerIdRepository()
        val first = gameApplication {
            name = "same-game"
            install(WorkerIdModule(repository, WorkerIdModuleOptions(range = WorkerIdRange.of(1, 2))))
        }
        val second = gameApplication {
            name = "same-game"
            install(WorkerIdModule(repository, WorkerIdModuleOptions(range = WorkerIdRange.of(1, 2))))
        }

        first.launch()
        second.launch()

        val firstRuntime = first.services.get<WorkerIdRuntime>()
        val secondRuntime = second.services.get<WorkerIdRuntime>()

        assertNotEquals(firstRuntime.lease.owner, secondRuntime.lease.owner)
        assertNotEquals(firstRuntime.id, secondRuntime.id)

        second.stop()
        first.stop()
    }

    @Test
    fun runtimeLeaseTracksRenewedLease() = runBlocking {
        val repository = InMemoryWorkerIdRepository()
        val app = gameApplication {
            install(
                WorkerIdModule(
                    repository,
                    WorkerIdModuleOptions(
                        range = WorkerIdRange.of(1, 1),
                        ttl = Duration.ofMillis(200),
                        renewInterval = Duration.ofMillis(50),
                    ),
                ),
            )
        }

        app.launch()
        val runtime = app.services.get<WorkerIdRuntime>()
        val oldExpiresAt = runtime.lease.expiresAt

        kotlinx.coroutines.delay(120)

        val newExpiresAt = runtime.lease.expiresAt
        app.stop()

        kotlin.test.assertTrue(newExpiresAt.isAfter(oldExpiresAt))
    }

    @Test
    fun renewRetriesTransientRepositoryFailures() = runBlocking {
        val repository = FlakyRenewRepository(failures = 2)
        val app = gameApplication {
            install(
                WorkerIdModule(
                    repository,
                    WorkerIdModuleOptions(
                        range = WorkerIdRange.of(1, 1),
                        ttl = Duration.ofMillis(400),
                        renewInterval = Duration.ofMillis(50),
                    ),
                ),
            )
        }

        app.launch()
        val runtime = app.services.get<WorkerIdRuntime>()
        val oldExpiresAt = runtime.lease.expiresAt

        delay(220)

        assertFalse(runtime.lost)
        assertTrue(runtime.lease.expiresAt.isAfter(oldExpiresAt))
        assertNotNull(app.services.get<IdGenerator>().nextId())

        app.stop()
    }

    @Test
    fun idGeneratorFailsClosedAfterLeaseIsLost() = runBlocking {
        val repository = LostOnRenewRepository()
        val app = gameApplication {
            install(
                WorkerIdModule(
                    repository,
                    WorkerIdModuleOptions(
                        range = WorkerIdRange.of(1, 1),
                        ttl = Duration.ofMillis(200),
                        renewInterval = Duration.ofMillis(50),
                    ),
                ),
            )
        }

        app.launch()
        val runtime = app.services.get<WorkerIdRuntime>()
        val generator = app.services.get<IdGenerator>()

        delay(100)

        assertTrue(runtime.lost)
        assertFailsWith<WorkerIdLeaseLostException> {
            generator.nextId()
        }

        app.stop()
    }

    private class FlakyRenewRepository(
        private var failures: Int,
    ) : WorkerIdRepository {
        private val delegate = InMemoryWorkerIdRepository()

        override suspend fun acquire(
            owner: WorkerIdOwner,
            range: WorkerIdRange,
            ttl: Duration,
            now: Instant,
        ): WorkerIdLease {
            return delegate.acquire(owner, range, ttl, now)
        }

        override suspend fun renew(lease: WorkerIdLease, ttl: Duration, now: Instant): WorkerIdLease? {
            if (failures > 0) {
                failures--
                throw IllegalStateException("repository unavailable")
            }
            return delegate.renew(lease, ttl, now)
        }

        override suspend fun release(lease: WorkerIdLease): Boolean {
            return delegate.release(lease)
        }

        override suspend fun leases(now: Instant): List<WorkerIdLease> {
            return delegate.leases(now)
        }
    }

    private class LostOnRenewRepository : WorkerIdRepository {
        private val delegate = InMemoryWorkerIdRepository()

        override suspend fun acquire(
            owner: WorkerIdOwner,
            range: WorkerIdRange,
            ttl: Duration,
            now: Instant,
        ): WorkerIdLease {
            return delegate.acquire(owner, range, ttl, now)
        }

        override suspend fun renew(lease: WorkerIdLease, ttl: Duration, now: Instant): WorkerIdLease? {
            return null
        }

        override suspend fun release(lease: WorkerIdLease): Boolean {
            return delegate.release(lease)
        }

        override suspend fun leases(now: Instant): List<WorkerIdLease> {
            return delegate.leases(now)
        }
    }
}
