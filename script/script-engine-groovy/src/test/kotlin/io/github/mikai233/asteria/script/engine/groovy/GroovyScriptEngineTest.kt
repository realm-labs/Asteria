package io.github.mikai233.asteria.script.engine.groovy

import io.github.mikai233.asteria.script.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class GroovyScriptEngineTest {
    @Test
    fun compilesBlockingScriptFunction() = runBlocking {
        val body = """
            import io.github.mikai233.asteria.script.BlockingScriptFunction
            import io.github.mikai233.asteria.script.ScriptContext
            import io.github.mikai233.asteria.script.ScriptExecutionResult

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
            import io.github.mikai233.asteria.script.NodeScript
            import io.github.mikai233.asteria.script.NodeScriptContext
            import io.github.mikai233.asteria.script.ScriptExecutionResult

            class TestNodeScript extends NodeScript {
                ScriptExecutionResult executeNode(NodeScriptContext context) {
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
