package io.github.realmlabs.asteria.script.engine.groovy

import io.github.realmlabs.asteria.script.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class GroovyScriptEngineTest {
    @Test
    fun compilesTopLevelGroovyScriptWithBindings() = runBlocking {
        val body = """
            import io.github.realmlabs.asteria.script.ScriptExecutionResult

            assert context != null
            assert runtime.name == 'test'
            assert services == runtime.services
            assert request == null
            assert artifact.engine == 'groovy'
            assert metadata.attributes.isEmpty()

            return new ScriptExecutionResult('inline-groovy-test', true, runtime.name, null, null, null)
        """.trimIndent()

        val compiled = GroovyScriptEngine().compile(
            ScriptArtifact("inline-groovy", "groovy", body.toByteArray()),
        )

        assertEquals(
            ScriptExecutionResult("inline-groovy-test", true, "test"),
            compiled.execute(TestScriptContext),
        )
    }

    @Test
    fun compilesTopLevelNodeGroovyScriptWithNodeBindings() = runBlocking {
        val body = """
            import io.github.realmlabs.asteria.script.ScriptExecutionResult

            assert target != null
            assert nodeAddress == 'pekko://test@127.0.0.1:25520'

            return new ScriptExecutionResult(request.executionId, true, target.toString(), null, nodeAddress, null)
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

        assertEquals(
            ScriptExecutionResult(
                executionId = "inline-node-groovy-test",
                success = true,
                target = ScriptTarget.AllNodes.toString(),
                nodeAddress = "pekko://test@127.0.0.1:25520",
            ),
            compiled.execute(NodeScriptContext(TestScriptContext.runtime, request)),
        )
    }

    @Test
    fun compilesBlockingScriptFunction() = runBlocking {
        val body = """
            import io.github.realmlabs.asteria.script.BlockingScriptFunction
            import io.github.realmlabs.asteria.script.ScriptContext
            import io.github.realmlabs.asteria.script.ScriptExecutionResult

            class TestGroovyScript implements BlockingScriptFunction {
                ScriptExecutionResult execute(ScriptContext context) {
                    return new ScriptExecutionResult("groovy-test", true, "groovy", null, null, null)
                }
            }
        """.trimIndent()

        val compiled = GroovyScriptEngine().compile(
            ScriptArtifact("test-groovy", "groovy", body.toByteArray()),
        )

        assertEquals(
            ScriptExecutionResult("groovy-test", true, "groovy"),
            compiled.execute(TestScriptContext),
        )
    }

    @Test
    fun compilesTypedNodeScript() = runBlocking {
        val body = """
            import io.github.realmlabs.asteria.script.NodeScript
            import io.github.realmlabs.asteria.script.NodeScriptContext
            import io.github.realmlabs.asteria.script.ScriptExecutionResult
            import io.github.realmlabs.asteria.core.NodeRuntime

            class TestNodeScript extends NodeScript<NodeRuntime> {
                ScriptExecutionResult executeNode(NodeScriptContext<NodeRuntime> context) {
                    return new ScriptExecutionResult(context.request.executionId, true, context.target.toString(), null, context.nodeAddress, null)
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

        assertEquals(
            ScriptExecutionResult(
                executionId = "node-groovy-test",
                success = true,
                target = ScriptTarget.AllNodes.toString(),
                nodeAddress = "pekko://test@127.0.0.1:25520",
            ),
            compiled.execute(NodeScriptContext(TestScriptContext.runtime, request)),
        )
    }
}
