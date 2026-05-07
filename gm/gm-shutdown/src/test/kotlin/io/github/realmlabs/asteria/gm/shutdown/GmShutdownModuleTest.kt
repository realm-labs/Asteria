package io.github.realmlabs.asteria.gm.shutdown

import io.github.realmlabs.asteria.core.gameApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GmShutdownModuleTest {
    @Test
    fun `module registers shutdown operations`() = runBlocking {
        val app = gameApplication {
            install(
                gmShutdownModule {
                    phase("prepare") {
                        step("mark") { GmShutdownStepResult.succeeded() }
                    }
                },
            )
        }

        app.launch()
        try {
            val operations = app.services.find(GmShutdownOperations::class)
            val coordinator = app.services.find(GmShutdownCoordinator::class)

            assertNotNull(operations)
            assertNotNull(coordinator)
            assertEquals("game-shutdown", operations.plan.name)
        } finally {
            app.stop()
        }
    }
}
