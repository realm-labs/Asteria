package io.github.realmlabs.asteria.script

import io.github.realmlabs.asteria.actor.AsteriaActor
import io.github.realmlabs.asteria.core.NodeRuntime
import io.github.realmlabs.asteria.core.ServiceRegistry

/**
 * Runtime view exposed to compiled scripts.
 *
 * [resources] resolves through a registered [ScriptResourceResolver] when one exists; otherwise local file and `file:`
 * references are used. [cancellation] is non-cancellable for ad-hoc executions unless a [ScriptCancellationProvider] is
 * registered and can recognize the request metadata.
 */
interface ScriptContext {
    val runtime: NodeRuntime
    val services: ServiceRegistry
    val request: ScriptExecutionRequest?
        get() = null
    val artifact: ScriptArtifact
    val metadata: ScriptExecutionMetadata
        get() = request?.metadata ?: ScriptExecutionMetadata()
    val resources: ScriptResources
        get() = ScriptResources(metadata.resources, services.find<ScriptResourceResolver>())
    val tables: ScriptResourceTableReader
        get() = ScriptResourceTableReader(resources)
    val cancellation: ScriptCancellationToken
        get() = request
            ?.let { services.find<ScriptCancellationProvider>()?.token(it) }
            ?: NonCancellableScriptToken
}

data class NodeScriptContext<N : NodeRuntime>(
    override val runtime: N,
    override val request: ScriptExecutionRequest,
) : ScriptContext {
    override val services: ServiceRegistry get() = runtime.services
    override val artifact: ScriptArtifact get() = request.artifact
    val target: ScriptTarget get() = request.target
    val nodeAddress: String? get() = request.nodeAddress
}

/**
 * Context for scripts executed inside an actor.
 *
 * Actor scripts can inspect the target and actor path that routed the request, but those values are diagnostics and may
 * be absent for direct in-process use.
 */
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
