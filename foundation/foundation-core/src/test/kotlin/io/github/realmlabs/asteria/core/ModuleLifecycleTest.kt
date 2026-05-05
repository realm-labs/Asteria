package io.github.realmlabs.asteria.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

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
                "uninstall:player-node:player:1",
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
                "uninstall:demo:world:0",
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
                "uninstall:feature-b",
                "uninstall:feature-a",
                "uninstall:runtime",
            ),
            events,
        )
    }

    @Test
    fun `launch failure stops started modules and uninstalls installed modules before rethrowing`() = runBlocking {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("feature-b failed")
        val app = gameApplication {
            name = "demo"
            install(NamedRecordingModule("runtime", events))
            install(FailingModule("feature-b", events, startFailure = failure))
            install(NamedRecordingModule("feature-c", events))
        }

        val thrown = assertFailsWith<IllegalStateException> {
            app.launch()
        }

        assertSame(failure, thrown)
        assertEquals(NodeState.Stopped, app.state)
        assertEquals(
            listOf(
                "install:runtime",
                "install:feature-b",
                "install:feature-c",
                "start:runtime",
                "start:feature-b",
                "stop:runtime",
                "uninstall:feature-c",
                "uninstall:feature-b",
                "uninstall:runtime",
            ),
            events,
        )
    }

    @Test
    fun `install failure uninstalls modules that already installed before rethrowing`() = runBlocking {
        val events = mutableListOf<String>()
        val failure = IllegalArgumentException("install failed")
        val app = gameApplication {
            name = "demo"
            install(NamedRecordingModule("runtime", events))
            install(FailingModule("feature-b", events, installFailure = failure))
            install(NamedRecordingModule("feature-c", events))
        }

        val thrown = assertFailsWith<IllegalArgumentException> {
            app.launch()
        }

        assertSame(failure, thrown)
        assertEquals(NodeState.Stopped, app.state)
        assertEquals(
            listOf(
                "install:runtime",
                "install:feature-b",
                "uninstall:runtime",
            ),
            events,
        )
    }

    @Test
    fun `rollback cleanup failures are suppressed on startup failure`() = runBlocking {
        val events = mutableListOf<String>()
        val startupFailure = IllegalStateException("start failed")
        val cleanupFailure = IllegalStateException("stop failed")
        val app = gameApplication {
            name = "demo"
            install(FailingModule("runtime", events, stopFailure = cleanupFailure))
            install(FailingModule("feature", events, startFailure = startupFailure))
        }

        val thrown = assertFailsWith<IllegalStateException> {
            app.launch()
        }

        assertSame(startupFailure, thrown)
        assertEquals(listOf(cleanupFailure), thrown.suppressed.toList())
        assertEquals(NodeState.Stopped, app.state)
        assertEquals(
            listOf(
                "install:runtime",
                "install:feature",
                "start:runtime",
                "start:feature",
                "stop:runtime",
                "uninstall:feature",
                "uninstall:runtime",
            ),
            events,
        )
    }

    @Test
    fun `state listener startup failure rolls lifecycle back before rethrowing`() = runBlocking {
        val failure = IllegalStateException("starting listener failed")
        val app = gameApplication {
            name = "demo"
        }
        app.onState(NodeState.Starting) {
            throw failure
        }

        val thrown = assertFailsWith<IllegalStateException> {
            app.launch()
        }

        assertSame(failure, thrown)
        assertEquals(NodeState.Stopped, app.state)
    }

    @Test
    fun `stop attempts every started and installed module before throwing cleanup failure`() = runBlocking {
        val events = mutableListOf<String>()
        val stopFailure = IllegalStateException("runtime stop failed")
        val uninstallFailure = IllegalStateException("feature uninstall failed")
        val app = gameApplication {
            name = "demo"
            install(FailingModule("runtime", events, stopFailure = stopFailure))
            install(FailingModule("feature", events, uninstallFailure = uninstallFailure))
        }

        app.launch()
        val thrown = assertFailsWith<IllegalStateException> {
            app.stop()
        }

        assertSame(stopFailure, thrown)
        assertEquals(listOf(uninstallFailure), thrown.suppressed.toList())
        assertEquals(NodeState.Stopped, app.state)
        assertEquals(
            listOf(
                "install:runtime",
                "install:feature",
                "start:runtime",
                "start:feature",
                "stop:feature",
                "stop:runtime",
                "uninstall:feature",
                "uninstall:runtime",
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

    override suspend fun uninstall(context: ModuleContext) {
        events += "uninstall:${context.name}:${context.roles.joinToString { it.value }}:${context.entities.size}"
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

    override suspend fun uninstall(context: ModuleContext) {
        events += "uninstall:$name"
    }
}

private class FailingModule(
    override val name: String,
    private val events: MutableList<String>,
    private val installFailure: Throwable? = null,
    private val startFailure: Throwable? = null,
    private val stopFailure: Throwable? = null,
    private val uninstallFailure: Throwable? = null,
) : AsteriaModule {
    override suspend fun install(context: ModuleContext) {
        events += "install:$name"
        installFailure?.let { throw it }
    }

    override suspend fun start(context: ModuleContext) {
        events += "start:$name"
        startFailure?.let { throw it }
    }

    override suspend fun stop(context: ModuleContext) {
        events += "stop:$name"
        stopFailure?.let { throw it }
    }

    override suspend fun uninstall(context: ModuleContext) {
        events += "uninstall:$name"
        uninstallFailure?.let { throw it }
    }
}
