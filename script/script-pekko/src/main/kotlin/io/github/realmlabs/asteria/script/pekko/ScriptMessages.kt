package io.github.realmlabs.asteria.script.pekko

import io.github.realmlabs.asteria.message.ShardMessage
import io.github.realmlabs.asteria.script.ScriptArtifact
import io.github.realmlabs.asteria.script.ScriptExecutionCommand
import io.github.realmlabs.asteria.script.ScriptExecutionMetadata
import io.github.realmlabs.asteria.script.ScriptTarget

/**
 * Message delivered to actors that support actor-scoped script execution.
 *
 * [target] is preserved for diagnostics and policy checks after the runtime expands multi-target commands into a single
 * actor message.
 */
data class ExecuteActorScript(
    val executionId: String,
    val artifact: ScriptArtifact,
    val target: ScriptTarget? = null,
    val metadata: ScriptExecutionMetadata = ScriptExecutionMetadata(),
)

/**
 * Sharding envelope for entity-scoped scripts.
 *
 * [id] is the entity id used by the shard region. The embedded [target] normally contains the same single id so scripts
 * and audit sinks can see the effective target that was executed.
 */
data class ExecuteEntityActorScript(
    override val id: String,
    val executionId: String,
    val artifact: ScriptArtifact,
    val target: ScriptTarget? = null,
    val metadata: ScriptExecutionMetadata = ScriptExecutionMetadata(),
) : ShardMessage<String>

/**
 * Node-level script command propagated through distributed pub-sub.
 *
 * [originNodeAddress] is set by the publishing runtime actor and lets the origin ignore the pub-sub echo after it has
 * already executed the command locally.
 */
data class ExecuteNodeScript(
    val command: ScriptExecutionCommand,
    val originNodeAddress: String? = null,
)
