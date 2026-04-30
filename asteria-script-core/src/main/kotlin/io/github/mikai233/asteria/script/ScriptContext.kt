package io.github.mikai233.asteria.script

import io.github.mikai233.asteria.core.NodeRuntime
import io.github.mikai233.asteria.core.ServiceRegistry

interface ScriptContext {
    val runtime: NodeRuntime
    val services: ServiceRegistry
    val artifact: ScriptArtifact
}

data class NodeScriptContext(
    override val runtime: NodeRuntime,
    override val artifact: ScriptArtifact,
) : ScriptContext {
    override val services: ServiceRegistry get() = runtime.services
}

interface ActorScriptContext<A : Any> : ScriptContext {
    val actor: A
}

data class DefaultActorScriptContext<A : Any>(
    override val runtime: NodeRuntime,
    override val artifact: ScriptArtifact,
    override val actor: A,
) : ActorScriptContext<A> {
    override val services: ServiceRegistry get() = runtime.services
}
