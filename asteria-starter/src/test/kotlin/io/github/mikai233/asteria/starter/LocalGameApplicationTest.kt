package io.github.mikai233.asteria.starter

import io.github.mikai233.asteria.cluster.pekko.PekkoRuntime
import io.github.mikai233.asteria.core.NodeState
import io.github.mikai233.asteria.rpc.RpcEntityIdRegistry
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LocalGameApplicationTest {
    @Test
    fun localRuntimeLaunchesAndStops() = runBlocking {
        val app = localGameApplication {
            name = "asteria-test-${System.nanoTime()}"
            role("test")
        }

        app.launch()
        assertEquals(NodeState.Started, app.state)
        assertNotNull(app.services.find<PekkoRuntime>())
        assertNotNull(app.services.find<RpcEntityIdRegistry>())

        app.stop()
        assertEquals(NodeState.Stopped, app.state)
    }
}
