package io.github.realmlabs.asteria.script

import io.github.realmlabs.asteria.core.NodeRuntime
import io.github.realmlabs.asteria.core.NodeState
import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.core.ServiceRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

class ScriptFunctionsTest {
    @Test
    fun `node script exposes typed runtime`() {
        val runtime = TestNodeRuntime("player-1")
        val artifact = ScriptArtifact("typed-node", "test", ByteArray(0))
        val request = ScriptExecutionRequest(
            executionId = "typed-node-test",
            target = ScriptTarget.Node(listOf("player-1")),
            artifact = artifact,
            scope = ScriptExecutionScope.Node,
        )

        val result = TestNodeScript().execute(NodeScriptContext(runtime, request))

        assertEquals(
            ScriptExecutionResult(
                executionId = "typed-node-test",
                success = true,
                target = "player-1",
            ),
            result,
        )
    }
}

private class TestNodeScript : NodeScript<TestNodeRuntime>() {
    override fun executeNode(context: NodeScriptContext<TestNodeRuntime>): ScriptExecutionResult {
        return ScriptExecutionResult(
            executionId = context.request.executionId,
            success = true,
            target = context.runtime.nodeId,
        )
    }
}

private data class TestNodeRuntime(
    val nodeId: String,
) : NodeRuntime {
    override val name: String = "test"
    override val roles: Set<RoleKey> = emptySet()
    override val state: NodeState = NodeState.Started
    override val services: ServiceRegistry = ServiceRegistry()
}
