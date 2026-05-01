package io.github.mikai233.asteria.script

import io.github.mikai233.asteria.actor.AsteriaActor
import io.github.mikai233.asteria.core.NodeRuntime
import io.github.mikai233.asteria.core.ServiceRegistry

interface ScriptContext {
    val runtime: NodeRuntime
    val services: ServiceRegistry
    val request: ScriptExecutionRequest?
        get() = null
    val artifact: ScriptArtifact
    val metadata: ScriptExecutionMetadata
        get() = request?.metadata ?: ScriptExecutionMetadata()
}

data class NodeScriptContext(
    override val runtime: NodeRuntime,
    override val request: ScriptExecutionRequest,
) : ScriptContext {
    override val services: ServiceRegistry get() = runtime.services
    override val artifact: ScriptArtifact get() = request.artifact
    val target: ScriptTarget get() = request.target
    val nodeAddress: String? get() = request.nodeAddress
}

interface ActorScriptContext<A : AsteriaActor<*>> : ScriptContext {
    val actor: A
    val target: ScriptTarget?
        get() = request?.target
    val actorPath: String?
        get() = request?.actorPath
}

data class DefaultActorScriptContext<A : AsteriaActor<*>>(
    override val runtime: NodeRuntime,
    override val request: ScriptExecutionRequest,
    override val actor: A,
) : ActorScriptContext<A> {
    override val services: ServiceRegistry get() = runtime.services
    override val artifact: ScriptArtifact get() = request.artifact
}
