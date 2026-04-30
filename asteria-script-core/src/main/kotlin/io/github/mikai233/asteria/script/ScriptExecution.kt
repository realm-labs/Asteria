package io.github.mikai233.asteria.script

data class ScriptExecutionCommand(
    val executionId: String,
    val target: ScriptTarget,
    val artifact: ScriptArtifact,
) {
    init {
        require(executionId.isNotBlank()) { "script execution id must not be blank" }
    }
}

data class ScriptExecutionResult(
    val executionId: String,
    val success: Boolean,
    val target: String? = null,
    val error: String? = null,
    val nodeAddress: String? = null,
    val actorPath: String? = null,
)

data class ScriptExecutionBatchResult(
    val executionId: String,
    val results: List<ScriptExecutionResult>,
) {
    val success: Boolean get() = results.isNotEmpty() && results.all { it.success }
}
