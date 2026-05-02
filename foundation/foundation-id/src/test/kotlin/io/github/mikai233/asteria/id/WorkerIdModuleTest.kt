package io.github.mikai233.asteria.id

import io.github.mikai233.asteria.core.gameApplication
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

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
}
