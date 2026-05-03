package io.github.realmlabs.asteria.script

import io.github.realmlabs.asteria.actor.AsteriaActor

abstract class NodeScript : BlockingScriptFunction {
    final override fun execute(context: ScriptContext): ScriptExecutionResult? {
        return executeNode(context.requireNodeContext())
    }

    abstract fun executeNode(context: NodeScriptContext): ScriptExecutionResult?
}

abstract class ActorScript<A : AsteriaActor<*>> : BlockingScriptFunction {
    final override fun execute(context: ScriptContext): ScriptExecutionResult? {
        val actorContext = context.requireActorContext()
        @Suppress("UNCHECKED_CAST")
        return executeActor(actorContext as ActorScriptContext<A>)
    }

    abstract fun executeActor(context: ActorScriptContext<A>): ScriptExecutionResult?
}

private fun ScriptContext.requireNodeContext(): NodeScriptContext {
    require(this is NodeScriptContext) {
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
