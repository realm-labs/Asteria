package io.github.realmlabs.asteria.script.pekko

import io.github.realmlabs.asteria.cluster.config.ClusterViewNode
import io.github.realmlabs.asteria.cluster.config.ClusterViewNodeStatus
import io.github.realmlabs.asteria.cluster.config.ClusterViewService
import io.github.realmlabs.asteria.cluster.config.ClusterViewSnapshot
import io.github.realmlabs.asteria.cluster.pekko.LocalPekkoClusterStartup
import io.github.realmlabs.asteria.cluster.pekko.PekkoRuntimeModule
import io.github.realmlabs.asteria.core.AsteriaModule
import io.github.realmlabs.asteria.core.ModuleContext
import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.core.gameApplication
import io.github.realmlabs.asteria.script.*
import kotlinx.coroutines.runBlocking
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.cluster.Cluster
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ScriptModuleTest {
    @Test
    fun nodeScriptExecutesWhenModuleIsInstalled() = runBlocking {
        val auditSink = RecordingScriptAuditSink()
        val app = gameApplication {
            name = "asteria-script-test-${System.nanoTime()}"
            role("script-test")
            install(PekkoRuntimeModule(LocalPekkoClusterStartup()))
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
            install(PekkoRuntimeModule(LocalPekkoClusterStartup()))
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
            assertEquals(
                listOf(ScriptExecutionResult(command.executionId, success = true, target = "echo")),
                batch.results
            )
        } finally {
            app.stop()
        }
    }

    @Test
    fun batchResultReportsMissingClusterViewTargets() = runBlocking {
        val clusterView = MutableClusterViewService()
        val app = gameApplication {
            name = "asteria-script-missing-target-test-${System.nanoTime()}"
            role("script-test")
            install(PekkoRuntimeModule(LocalPekkoClusterStartup()))
            install(ClusterViewTestModule(clusterView))
            install(
                ScriptModule {
                    allowNodeScripts = true
                    engine(DefaultResultScriptEngine)
                },
            )
        }

        try {
            app.launch()
            val system = app.services.get<ActorSystem>()
            val selfAddress = Cluster.get(system).selfAddress().toString()
            val missingAddress = "pekko://missing@127.0.0.1:2552"
            clusterView.nodes = listOf(
                ClusterViewNode(
                    nodeId = "self",
                    address = selfAddress,
                    appName = app.name,
                    version = null,
                    roles = setOf(RoleKey("script-test")),
                    status = ClusterViewNodeStatus.Reachable,
                ),
                ClusterViewNode(
                    nodeId = "missing",
                    address = missingAddress,
                    appName = app.name,
                    version = null,
                    roles = setOf(RoleKey("script-test")),
                    status = ClusterViewNodeStatus.Expected,
                ),
            )
            val scriptRuntime = app.services.find<ScriptRuntime>()
            assertNotNull(scriptRuntime)

            val command = ScriptExecutionCommand(
                executionId = "test-script",
                target = ScriptTarget.AllNodes,
                artifact = ScriptArtifact("default", DefaultResultScriptEngine.name, ByteArray(0)),
            )
            val batch = scriptRuntime.executeAll(command, 200.milliseconds)

            assertFalse(batch.success)
            assertEquals(
                listOf(
                    ScriptExecutionTarget(ScriptExecutionTargetType.Node, selfAddress),
                    ScriptExecutionTarget(ScriptExecutionTargetType.Node, missingAddress),
                ),
                batch.expectedTargets,
            )
            assertEquals(
                listOf(ScriptExecutionTarget(ScriptExecutionTargetType.Node, missingAddress)),
                batch.missingTargets,
            )
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
            install(PekkoRuntimeModule(LocalPekkoClusterStartup()))
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
            install(PekkoRuntimeModule(LocalPekkoClusterStartup()))
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

    @Test
    fun scriptModuleRegistersConfiguredResourceResolver() = runBlocking {
        val cacheDirectory = createTempDirectory("asteria-script-resource-cache")
        val app = gameApplication {
            name = "asteria-script-resource-test-${System.nanoTime()}"
            role("script-test")
            install(PekkoRuntimeModule(LocalPekkoClusterStartup()))
            install(
                ScriptModule {
                    engine(EchoScriptEngine)
                    resourceCache(cacheDirectory)
                },
            )
        }

        try {
            app.launch()
            assertNotNull(app.services.find<ScriptResourceResolver>())
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

private object DefaultResultScriptEngine : ScriptEngine {
    override val name: String = "default-result"

    override fun compile(artifact: ScriptArtifact): CompiledScript {
        return CompiledScript { null }
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

private class MutableClusterViewService : ClusterViewService {
    var nodes: List<ClusterViewNode> = emptyList()

    override suspend fun snapshot(): ClusterViewSnapshot {
        return ClusterViewSnapshot(nodes)
    }
}

private class ClusterViewTestModule(
    private val view: ClusterViewService,
) : AsteriaModule {
    override val name: String = "cluster-view-test"

    override suspend fun install(context: ModuleContext) {
        context.services.register(ClusterViewService::class, view)
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
