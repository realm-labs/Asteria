package io.github.realmlabs.asteria.script.engine.groovy

import io.github.realmlabs.asteria.script.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class GroovyScriptEngineTest {
    @Test
    fun compilesTopLevelGroovyScriptWithBindings() = runBlocking {
        val body = """
            assert context != null
            assert runtime.name == 'test'
            assert services == runtime.services
            assert request == null
            assert artifact.engine == 'groovy'
            assert metadata.attributes.isEmpty()
        """.trimIndent()

        val compiled = GroovyScriptEngine().compile(
            ScriptArtifact("inline-groovy", "groovy", body.toByteArray()),
        )

        compiled.execute(TestScriptContext)
    }

    @Test
    fun compilesTopLevelNodeGroovyScriptWithNodeBindings() = runBlocking {
        val body = """
            assert target != null
            assert nodeAddress == 'pekko://test@127.0.0.1:25520'
        """.trimIndent()
        val artifact = ScriptArtifact("inline-node-groovy", "groovy", body.toByteArray())
        val request = ScriptExecutionRequest(
            executionId = "inline-node-groovy-test",
            target = ScriptTarget.AllNodes,
            artifact = artifact,
            scope = ScriptExecutionScope.Node,
            nodeAddress = "pekko://test@127.0.0.1:25520",
        )
        val compiled = GroovyScriptEngine().compile(artifact)

        compiled.execute(NodeScriptContext(TestScriptContext.runtime, request))
    }

    @Test
    fun compilesBlockingScriptFunction() = runBlocking {
        val body = """
            import io.github.realmlabs.asteria.script.BlockingScriptFunction
            import io.github.realmlabs.asteria.script.ScriptContext

            class TestGroovyScript implements BlockingScriptFunction {
                void execute(ScriptContext context) {
                    assert context != null
                }
            }
        """.trimIndent()

        val compiled = GroovyScriptEngine().compile(
            ScriptArtifact("test-groovy", "groovy", body.toByteArray()),
        )

        compiled.execute(TestScriptContext)
    }

    @Test
    fun compilesTypedNodeScript() = runBlocking {
        val body = """
            import io.github.realmlabs.asteria.script.NodeScript
            import io.github.realmlabs.asteria.script.NodeScriptContext
            import io.github.realmlabs.asteria.core.NodeRuntime

            class TestNodeScript extends NodeScript<NodeRuntime> {
                void executeNode(NodeScriptContext<NodeRuntime> context) {
                    assert context.request.executionId == 'node-groovy-test'
                    assert context.nodeAddress == 'pekko://test@127.0.0.1:25520'
                }
            }
        """.trimIndent()
        val artifact = ScriptArtifact("test-node-groovy", "groovy", body.toByteArray())
        val compiled = GroovyScriptEngine().compile(artifact)
        val request = ScriptExecutionRequest(
            executionId = "node-groovy-test",
            target = ScriptTarget.AllNodes,
            artifact = artifact,
            scope = ScriptExecutionScope.Node,
            nodeAddress = "pekko://test@127.0.0.1:25520",
        )

        compiled.execute(NodeScriptContext(TestScriptContext.runtime, request))
    }
}
