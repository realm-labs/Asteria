package io.github.mikai233.asteria.script.pekko

import io.github.mikai233.asteria.cluster.pekko.PekkoRuntimeModule
import io.github.mikai233.asteria.core.gameApplication
import io.github.mikai233.asteria.script.CompiledScript
import io.github.mikai233.asteria.script.ScriptAuditSink
import io.github.mikai233.asteria.script.ScriptArtifact
import io.github.mikai233.asteria.script.ScriptEngine
import io.github.mikai233.asteria.script.ScriptExecutionCommand
import io.github.mikai233.asteria.script.ScriptExecutionRequest
import io.github.mikai233.asteria.script.ScriptExecutionResult
import io.github.mikai233.asteria.script.ScriptRuntime
import io.github.mikai233.asteria.script.ScriptTarget
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ScriptModuleTest {
    @Test
    fun nodeScriptExecutesWhenModuleIsInstalled() = runBlocking {
        val auditSink = RecordingScriptAuditSink()
        val app = gameApplication {
            name = "asteria-script-test-${System.nanoTime()}"
            role("script-test")
            install(PekkoRuntimeModule.local())
            install(
                ScriptModule {
                    allowNodeScripts = true
                    engine(EchoScriptEngine)
                    auditSink(auditSink)
                },
            )
        }

        try {
            app.launch()
            val scriptRuntime = app.services.find<ScriptRuntime>()
            assertNotNull(scriptRuntime)

            val command = ScriptExecutionCommand(
                executionId = "test-script",
                target = ScriptTarget.AllNodes,
                artifact = ScriptArtifact("echo", EchoScriptEngine.name, ByteArray(0)),
            )
            val result = scriptRuntime.execute(command, 3.seconds)
            assertEquals(ScriptExecutionResult(command.executionId, success = true, target = "echo"), result)
            assertEquals(listOf("started:test-script", "completed:test-script:true"), auditSink.events)
        } finally {
            app.stop()
        }
    }

    @Test
    fun nodeScriptCanCollectBatchResults() = runBlocking {
        val app = gameApplication {
            name = "asteria-script-batch-test-${System.nanoTime()}"
            role("script-test")
            install(PekkoRuntimeModule.local())
            install(
                ScriptModule {
                    allowNodeScripts = true
                    engine(EchoScriptEngine)
                },
            )
        }

        try {
            app.launch()
            val scriptRuntime = app.services.find<ScriptRuntime>()
            assertNotNull(scriptRuntime)

            val command = ScriptExecutionCommand(
                executionId = "test-script",
                target = ScriptTarget.AllNodes,
                artifact = ScriptArtifact("echo", EchoScriptEngine.name, ByteArray(0)),
            )
            val batch = scriptRuntime.executeAll(command, 200.milliseconds)
            assertEquals(command.executionId, batch.executionId)
            assertTrue(batch.success)
            assertEquals(listOf(ScriptExecutionResult(command.executionId, success = true, target = "echo")), batch.results)
        } finally {
            app.stop()
        }
    }

    @Test
    fun completedNodeScriptIsReplayedByExecutionIdAndTarget() = runBlocking {
        val auditSink = RecordingScriptAuditSink()
        val engine = CountingScriptEngine()
        val app = gameApplication {
            name = "asteria-script-idempotent-test-${System.nanoTime()}"
            role("script-test")
            install(PekkoRuntimeModule.local())
            install(
                ScriptModule {
                    allowNodeScripts = true
                    engine(engine)
                    auditSink(auditSink)
                },
            )
        }

        try {
            app.launch()
            val scriptRuntime = app.services.find<ScriptRuntime>()
            assertNotNull(scriptRuntime)

            val command = ScriptExecutionCommand(
                executionId = "idempotent-script",
                target = ScriptTarget.AllNodes,
                artifact = ScriptArtifact("counting", engine.name, ByteArray(0)),
            )
            val first = scriptRuntime.execute(command, 3.seconds)
            val second = scriptRuntime.execute(command, 3.seconds)

            assertEquals(first, second)
            assertEquals(1, engine.executions)
            assertEquals(
                listOf(
                    "started:idempotent-script",
                    "completed:idempotent-script:true",
                    "replayed:idempotent-script:true",
                ),
                auditSink.events,
            )
        } finally {
            app.stop()
        }
    }

    @Test
    fun nodeScriptIsRejectedWhenNodeScriptsAreDisabled() = runBlocking {
        val auditSink = RecordingScriptAuditSink()
        val app = gameApplication {
            name = "asteria-script-denied-test-${System.nanoTime()}"
            role("script-test")
            install(PekkoRuntimeModule.local())
            install(
                ScriptModule {
                    engine(EchoScriptEngine)
                    auditSink(auditSink)
                },
            )
        }

        try {
            app.launch()
            val scriptRuntime = app.services.find<ScriptRuntime>()
            assertNotNull(scriptRuntime)

            val command = ScriptExecutionCommand(
                executionId = "denied-script",
                target = ScriptTarget.AllNodes,
                artifact = ScriptArtifact("echo", EchoScriptEngine.name, ByteArray(0)),
            )
            val result = scriptRuntime.execute(command, 3.seconds)
            assertEquals(command.executionId, result.executionId)
            assertFalse(result.success)
            assertEquals("node scripts are disabled", result.error)
            assertEquals(listOf("rejected:denied-script:node scripts are disabled"), auditSink.events)
        } finally {
            app.stop()
        }
    }
}

private object EchoScriptEngine : ScriptEngine {
    override val name: String = "echo"

    override fun compile(artifact: ScriptArtifact): CompiledScript {
        return CompiledScript {
            ScriptExecutionResult("test-script", success = true, target = name)
        }
    }
}

private class CountingScriptEngine : ScriptEngine {
    override val name: String = "counting"
    var executions: Int = 0
        private set

    override fun compile(artifact: ScriptArtifact): CompiledScript {
        return CompiledScript {
            executions += 1
            ScriptExecutionResult("idempotent-script", success = true, target = name)
        }
    }
}

private class RecordingScriptAuditSink : ScriptAuditSink {
    val events: MutableList<String> = mutableListOf()

    override suspend fun started(request: ScriptExecutionRequest) {
        events.add("started:${request.executionId}")
    }

    override suspend fun completed(request: ScriptExecutionRequest, result: ScriptExecutionResult) {
        events.add("completed:${request.executionId}:${result.success}")
    }

    override suspend fun replayed(request: ScriptExecutionRequest, result: ScriptExecutionResult) {
        events.add("replayed:${request.executionId}:${result.success}")
    }

    override suspend fun rejected(request: ScriptExecutionRequest, reason: String) {
        events.add("rejected:${request.executionId}:$reason")
    }
}
