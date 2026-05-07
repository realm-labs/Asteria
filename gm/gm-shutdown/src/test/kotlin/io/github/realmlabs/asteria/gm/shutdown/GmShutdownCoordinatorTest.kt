package io.github.realmlabs.asteria.gm.shutdown

import io.github.realmlabs.asteria.core.ServiceRegistry
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GmShutdownCoordinatorTest {
    @Test
    fun `coordinator executes phases and steps in declaration order`() = runBlocking {
        val executed = mutableListOf<String>()
        val coordinator = GmShutdownCoordinator(
            plan = gmShutdownPlan {
                phase("gateway") {
                    step("stop-accepting") {
                        executed += "gateway.stop-accepting"
                        GmShutdownStepResult.succeeded()
                    }
                    step("close-sessions") {
                        executed += "gateway.close-sessions"
                        GmShutdownStepResult.succeeded()
                    }
                }
                phase("players") {
                    step("flush-players") {
                        executed += "players.flush-players"
                        GmShutdownStepResult.succeeded("players flushed")
                    }
                }
            },
            services = ServiceRegistry(),
        )

        val status = coordinator.run(request())

        assertEquals(
            listOf("gateway.stop-accepting", "gateway.close-sessions", "players.flush-players"),
            executed,
        )
        assertEquals(GmShutdownRunState.Succeeded, status.state)
        assertEquals(GmShutdownPhaseState.Succeeded, status.phases[0].state)
        assertEquals(GmShutdownStepState.Succeeded, status.phases[1].steps.single().state)
        assertNull(status.currentPhase)
        assertNull(status.currentStep)
    }

    @Test
    fun `coordinator stops on fatal failed step result`() = runBlocking {
        val executed = mutableListOf<String>()
        val coordinator = GmShutdownCoordinator(
            plan = gmShutdownPlan {
                phase("players") {
                    step("flush-players") {
                        executed += "players.flush-players"
                        GmShutdownStepResult.failed("pending player writes")
                    }
                    step("stop-players") {
                        executed += "players.stop-players"
                        GmShutdownStepResult.succeeded()
                    }
                }
                phase("worlds") {
                    step("flush-worlds") {
                        executed += "worlds.flush-worlds"
                        GmShutdownStepResult.succeeded()
                    }
                }
            },
            services = ServiceRegistry(),
        )

        val status = coordinator.run(request())

        assertEquals(listOf("players.flush-players"), executed)
        assertEquals(GmShutdownRunState.Failed, status.state)
        assertEquals(GmShutdownPhaseState.Failed, status.phases[0].state)
        assertEquals(GmShutdownStepState.Failed, status.phases[0].steps[0].state)
        assertEquals(GmShutdownStepState.Pending, status.phases[0].steps[1].state)
        assertEquals(GmShutdownPhaseState.Pending, status.phases[1].state)
    }

    @Test
    fun `non fatal failed step records failure and continues`() = runBlocking {
        val executed = mutableListOf<String>()
        val coordinator = GmShutdownCoordinator(
            plan = gmShutdownPlan {
                phase("prepare") {
                    step("broadcast", continueOnFailure = true) {
                        executed += "prepare.broadcast"
                        GmShutdownStepResult.failed("broadcast backend unavailable")
                    }
                    step("mark-shutdown") {
                        executed += "prepare.mark-shutdown"
                        GmShutdownStepResult.succeeded()
                    }
                }
            },
            services = ServiceRegistry(),
        )

        val status = coordinator.run(request())

        assertEquals(listOf("prepare.broadcast", "prepare.mark-shutdown"), executed)
        assertEquals(GmShutdownRunState.Succeeded, status.state)
        assertEquals(GmShutdownStepState.Failed, status.phases.single().steps[0].state)
        assertEquals(GmShutdownStepState.Succeeded, status.phases.single().steps[1].state)
    }

    private fun request(): GmShutdownRequest {
        return GmShutdownRequest(
            operator = "gm-admin",
            reason = "maintenance",
        )
    }
}
