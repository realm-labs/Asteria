package io.github.realmlabs.asteria.script

import io.github.realmlabs.asteria.actor.AsteriaActor
import io.github.realmlabs.asteria.core.NodeRuntime

abstract class NodeScript<N : NodeRuntime> : BlockingScriptFunction {
    final override fun execute(context: ScriptContext) {
        val nodeContext = context.requireNodeContext()
        @Suppress("UNCHECKED_CAST")
        executeNode(nodeContext as NodeScriptContext<N>)
    }

    abstract fun executeNode(context: NodeScriptContext<N>)
}

abstract class ActorScript<A : AsteriaActor<*>> : BlockingScriptFunction {
    final override fun execute(context: ScriptContext) {
        val actorContext = context.requireActorContext()
        @Suppress("UNCHECKED_CAST")
        executeActor(actorContext as ActorScriptContext<A>)
    }

    abstract fun executeActor(context: ActorScriptContext<A>)
}

private fun ScriptContext.requireNodeContext(): NodeScriptContext<*> {
    require(this is NodeScriptContext<*>) {
        "script requires ${NodeScriptContext::class.qualifiedName}, but context was ${this::class.qualifiedName}"
    }
    return this
}

private fun ScriptContext.requireActorContext(): ActorScriptContext<*> {
    require(this is ActorScriptContext<*>) {
        "script requires ${ActorScriptContext::class.qualifiedName}, but context was ${this::class.qualifiedName}"
    }
    return this
}
