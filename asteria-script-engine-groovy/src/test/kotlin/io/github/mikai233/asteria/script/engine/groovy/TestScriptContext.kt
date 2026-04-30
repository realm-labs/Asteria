package io.github.mikai233.asteria.script.engine.groovy

import io.github.mikai233.asteria.core.NodeRuntime
import io.github.mikai233.asteria.core.NodeState
import io.github.mikai233.asteria.core.RoleKey
import io.github.mikai233.asteria.core.ServiceRegistry
import io.github.mikai233.asteria.script.ScriptArtifact
import io.github.mikai233.asteria.script.ScriptContext

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
