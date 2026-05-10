package io.github.realmlabs.asteria.script

/**
 * One script execution request.
 *
 * [executionId] is the idempotency/audit key used by script runners and job attempts. Job items derive attempt ids from
 * the submitted id using `source.item.attempt`. The target describes where the script should run; runtimes may reject
 * targets they cannot route.
 */
data class ScriptExecutionCommand(
    val executionId: String,
    val target: ScriptTarget,
    val artifact: ScriptArtifact,
    val metadata: ScriptExecutionMetadata = ScriptExecutionMetadata(),
) {
    init {
        require(executionId.isNotBlank()) { "script execution id must not be blank" }
    }
}

/**
 * Operator and policy metadata attached to a script execution.
 *
 * [attributes] carries framework and application extension keys; script-job cancellation depends on the `script.jobId`,
 * `script.itemId`, and `script.attempt` keys that the job service adds to item attempts. Resource names must be unique
 * so scripts can resolve them unambiguously.
 */
data class ScriptExecutionMetadata(
    val requester: String? = null,
    val reason: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val resources: List<ScriptResourceRef> = emptyList(),
) {
    init {
        requester?.let { require(it.isNotBlank()) { "script requester must not be blank" } }
        reason?.let { require(it.isNotBlank()) { "script execution reason must not be blank" } }
        attributes.forEach { (key, _) ->
            require(key.isNotBlank()) { "script execution metadata attribute key must not be blank" }
        }
        resources.map { it.name }.let { names ->
            require(names.distinct().size == names.size) { "script resource names must be unique" }
        }
    }
}

/**
 * Result produced by one target execution.
 *
 * [target], [nodeAddress], and [actorPath] are best-effort routing diagnostics. A failed result should put the
 * operator-facing reason in [error].
 */
data class ScriptExecutionResult(
    val executionId: String,
    val success: Boolean,
    val target: String? = null,
    val error: String? = null,
    val nodeAddress: String? = null,
    val actorPath: String? = null,
)

/**
 * One target that a batch execution expected to acknowledge.
 */
data class ScriptExecutionTarget(
    val type: ScriptExecutionTargetType,
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "script execution target value must not be blank" }
    }
}

enum class ScriptExecutionTargetType {
    Node,
    ActorPath,
    Entity,
    Singleton,
}

data class ScriptExecutionBatchResult(
    val executionId: String,
    val results: List<ScriptExecutionResult>,
    val expectedTargets: List<ScriptExecutionTarget> = emptyList(),
    val missingTargets: List<ScriptExecutionTarget> = emptyList(),
) {
    /**
     * Empty batches are not considered successful; they usually mean no target acknowledged the execution.
     */
    val success: Boolean get() = results.isNotEmpty() && results.all { it.success } && missingTargets.isEmpty()
}
