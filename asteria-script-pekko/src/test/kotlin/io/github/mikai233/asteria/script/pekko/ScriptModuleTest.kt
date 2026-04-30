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
import io.github.mikai233.asteria.script.ScriptTarget
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.pattern.Patterns
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

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
            val scriptRuntime = app.services.find<PekkoScriptRuntime>()
            assertNotNull(scriptRuntime)

            val command = ScriptExecutionCommand(
                executionId = "test-script",
                target = ScriptTarget.AllNodes,
                artifact = ScriptArtifact("echo", EchoScriptEngine.name, ByteArray(0)),
            )
            val result = Patterns.ask(scriptRuntime.actor, command, 3.seconds.toJavaDuration()).await()
            assertEquals(ScriptExecutionResult(command.executionId, success = true, target = "echo"), result)
            assertEquals(listOf("started:test-script", "completed:test-script:true"), auditSink.events)
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
            val scriptRuntime = app.services.find<PekkoScriptRuntime>()
            assertNotNull(scriptRuntime)

            val command = ScriptExecutionCommand(
                executionId = "denied-script",
                target = ScriptTarget.AllNodes,
                artifact = ScriptArtifact("echo", EchoScriptEngine.name, ByteArray(0)),
            )
            val result = Patterns.ask(scriptRuntime.actor, command, 3.seconds.toJavaDuration()).await() as ScriptExecutionResult
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

private class RecordingScriptAuditSink : ScriptAuditSink {
    val events: MutableList<String> = mutableListOf()

    override suspend fun started(request: ScriptExecutionRequest) {
        events.add("started:${request.executionId}")
    }

    override suspend fun completed(request: ScriptExecutionRequest, result: ScriptExecutionResult) {
        events.add("completed:${request.executionId}:${result.success}")
    }

    override suspend fun rejected(request: ScriptExecutionRequest, reason: String) {
        events.add("rejected:${request.executionId}:$reason")
    }
}
