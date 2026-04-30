package io.github.mikai233.asteria.script.engine.groovy

import io.github.mikai233.asteria.script.ScriptArtifact
import io.github.mikai233.asteria.script.ScriptExecutionResult
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
}
