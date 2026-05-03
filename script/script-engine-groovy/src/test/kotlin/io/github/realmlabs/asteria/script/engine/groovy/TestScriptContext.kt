package io.github.realmlabs.asteria.script.engine.groovy

import io.github.realmlabs.asteria.core.NodeRuntime
import io.github.realmlabs.asteria.core.NodeState
import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.core.ServiceRegistry
import io.github.realmlabs.asteria.script.ScriptArtifact
import io.github.realmlabs.asteria.script.ScriptContext

object TestScriptContext : ScriptContext {
    override val runtime: NodeRuntime = object : NodeRuntime {
        override val name: String = "test"
        override val roles: Set<RoleKey> = emptySet()
        override val state: NodeState = NodeState.Started
        override val services: ServiceRegistry = ServiceRegistry()
    }

    override val services: ServiceRegistry get() = runtime.services
    override val artifact: ScriptArtifact = ScriptArtifact("test", "groovy", ByteArray(0))
}
