package io.github.realmlabs.asteria.script

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
