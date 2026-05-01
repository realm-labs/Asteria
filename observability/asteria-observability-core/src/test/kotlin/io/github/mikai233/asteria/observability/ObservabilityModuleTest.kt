package io.github.mikai233.asteria.observability

import io.github.mikai233.asteria.core.gameApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ObservabilityModuleTest {
    @Test
    fun moduleRegistersObservabilityServices() = runBlocking {
        val app = gameApplication {
            install(ObservabilityModule())
        }

        app.launch()
        try {
            assertNotNull(app.services.find<Observability>())
            assertNotNull(app.services.find<Tracer>())
            assertNotNull(app.services.find<Metrics>())
        } finally {
            app.stop()
        }
    }

    @Test
    fun noopImplementationsPreserveReturnValues() = runBlocking {
        val spanResult = NoopTracer.span("test-span") {
            event("test-event")
            42
        }
        val timerResult = NoopMetrics.timer("test-timer").record {
            "ok"
        }

        assertEquals(42, spanResult)
        assertEquals("ok", timerResult)
    }
}
