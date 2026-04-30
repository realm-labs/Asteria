package io.github.mikai233.asteria.script.pekko

import org.apache.pekko.actor.ActorRef

data class PekkoScriptRuntime(
    val actor: ActorRef,
)
