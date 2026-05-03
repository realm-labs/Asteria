package io.github.realmlabs.asteria.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleLifecycleTest {
    @Test
    fun `application definition can drive external node runtime`() = runBlocking {
        val events = mutableListOf<String>()
        val app = gameApplication {
            name = "demo"
            role("player")
            entity<Long>("player") {
                role("player")
            }
            install(RecordingModule(events))
        }
        val node = TestPlayerNode()
        val lifecycle = app.bind(node) { node.state = it }

        lifecycle.launch()
        lifecycle.stop()

        assertEquals(NodeState.Stopped, lifecycle.state)
        assertEquals(NodeState.Stopped, node.state)
        assertEquals(
            listOf(
                "install:player-node:player:1",
                "start:player-node:player:1",
                "stop:player-node:player:1",
            ),
            events,
        )
    }

    @Test
    fun `application launch still uses the same module lifecycle`() = runBlocking {
        val events = mutableListOf<String>()
        val app = gameApplication {
            name = "demo"
            role("world")
            install(RecordingModule(events))
        }

        app.launch()
        app.stop()

        assertEquals(NodeState.Stopped, app.state)
        assertEquals(
            listOf(
                "install:demo:world:0",
                "start:demo:world:0",
                "stop:demo:world:0",
            ),
            events,
        )
    }

    @Test
    fun `lifecycle can stop modules after an anchor module once`() = runBlocking {
        val events = mutableListOf<String>()
        val app = gameApplication {
            name = "demo"
            install(NamedRecordingModule("runtime", events))
            install(NamedRecordingModule("feature-a", events))
            install(NamedRecordingModule("feature-b", events))
        }
        val lifecycle = app.bind(TestPlayerNode())

        lifecycle.launch()
        lifecycle.stopAfter("runtime")
        lifecycle.stop()

        assertEquals(
            listOf(
                "install:runtime",
                "install:feature-a",
                "install:feature-b",
                "start:runtime",
                "start:feature-a",
                "start:feature-b",
                "stop:feature-b",
                "stop:feature-a",
                "stop:runtime",
            ),
            events,
        )
    }
}

private class TestPlayerNode : NodeRuntime {
    override val name: String = "player-node"
    override val roles: Set<RoleKey> = setOf(RoleKey("player"))
    override var state: NodeState = NodeState.Unstarted
    override val services: ServiceRegistry = ServiceRegistry()
}

private class RecordingModule(
    private val events: MutableList<String>,
) : AsteriaModule {
    override val name: String = "recording"

    override suspend fun install(context: ModuleContext) {
        events += "install:${context.name}:${context.roles.joinToString { it.value }}:${context.entities.size}"
    }

    override suspend fun start(context: ModuleContext) {
        events += "start:${context.name}:${context.roles.joinToString { it.value }}:${context.entities.size}"
    }

    override suspend fun stop(context: ModuleContext) {
        events += "stop:${context.name}:${context.roles.joinToString { it.value }}:${context.entities.size}"
    }
}

private class NamedRecordingModule(
    override val name: String,
    private val events: MutableList<String>,
) : AsteriaModule {
    override suspend fun install(context: ModuleContext) {
        events += "install:$name"
    }

    override suspend fun start(context: ModuleContext) {
        events += "start:$name"
    }

    override suspend fun stop(context: ModuleContext) {
        events += "stop:$name"
    }
}
