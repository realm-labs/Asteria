package io.github.realmlabs.asteria.script.pekko

import io.github.realmlabs.asteria.actor.AsteriaActor
import io.github.realmlabs.asteria.core.NodeRuntime

/**
 * Convenience base class for actors that want a prebuilt [ActorScriptSupport] component.
 *
 * Compose [scriptReceive] into the actor states where GM scripts should be accepted. For example, include it in the
 * running state but omit it from a loading state that stashes all external messages.
 */
abstract class ScriptableAsteriaActor<N : NodeRuntime>(
    runtime: N,
) : AsteriaActor<N>(runtime) {
    protected val scripts: ActorScriptSupport = ActorScriptSupport(this)

    protected fun scriptReceive(): Receive {
        return scripts.receive()
    }
}
