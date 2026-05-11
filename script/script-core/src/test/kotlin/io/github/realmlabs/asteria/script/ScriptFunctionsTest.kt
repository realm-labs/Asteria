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

        TestNodeScript().execute(NodeScriptContext(runtime, request))

        assertEquals("player-1", runtime.lastNodeId)
    }
}

private class TestNodeScript : NodeScript<TestNodeRuntime>() {
    override fun executeNode(context: NodeScriptContext<TestNodeRuntime>) {
        context.runtime.lastNodeId = context.runtime.nodeId
    }
}

private class TestNodeRuntime(
    val nodeId: String,
) : NodeRuntime {
    var lastNodeId: String? = null
    override val name: String = "test"
    override val roles: Set<RoleKey> = emptySet()
    override val state: NodeState = NodeState.Started
    override val services: ServiceRegistry = ServiceRegistry()
}
