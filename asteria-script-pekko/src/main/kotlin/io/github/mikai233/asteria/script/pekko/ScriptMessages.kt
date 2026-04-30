package io.github.mikai233.asteria.script.pekko

import io.github.mikai233.asteria.message.ShardMessage
import io.github.mikai233.asteria.script.ScriptArtifact
import io.github.mikai233.asteria.script.ScriptExecutionCommand
import io.github.mikai233.asteria.script.ScriptTarget

data class ExecuteActorScript(
    val executionId: String,
    val artifact: ScriptArtifact,
    val target: ScriptTarget? = null,
)

data class ExecuteEntityActorScript(
    override val id: String,
    val executionId: String,
    val artifact: ScriptArtifact,
    val target: ScriptTarget? = null,
) : ShardMessage<String>

data class ExecuteNodeScript(
    val command: ScriptExecutionCommand,
    val originNodeAddress: String? = null,
)
