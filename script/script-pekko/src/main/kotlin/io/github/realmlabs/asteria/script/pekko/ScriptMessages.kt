package io.github.realmlabs.asteria.script.pekko

import io.github.realmlabs.asteria.message.ShardMessage
import io.github.realmlabs.asteria.script.ScriptArtifact
import io.github.realmlabs.asteria.script.ScriptExecutionCommand
import io.github.realmlabs.asteria.script.ScriptExecutionMetadata
import io.github.realmlabs.asteria.script.ScriptTarget

data class ExecuteActorScript(
    val executionId: String,
    val artifact: ScriptArtifact,
    val target: ScriptTarget? = null,
    val metadata: ScriptExecutionMetadata = ScriptExecutionMetadata(),
)

data class ExecuteEntityActorScript(
    override val id: String,
    val executionId: String,
    val artifact: ScriptArtifact,
    val target: ScriptTarget? = null,
    val metadata: ScriptExecutionMetadata = ScriptExecutionMetadata(),
) : ShardMessage<String>

data class ExecuteNodeScript(
    val command: ScriptExecutionCommand,
    val originNodeAddress: String? = null,
)
