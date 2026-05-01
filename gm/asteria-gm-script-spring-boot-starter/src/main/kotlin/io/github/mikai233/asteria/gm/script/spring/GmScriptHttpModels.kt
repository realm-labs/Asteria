package io.github.mikai233.asteria.gm.script.spring

import io.github.mikai233.asteria.core.EntityKind
import io.github.mikai233.asteria.core.RoleKey
import io.github.mikai233.asteria.core.SingletonName
import io.github.mikai233.asteria.script.ScriptArtifact
import io.github.mikai233.asteria.script.ScriptExecutionCommand
import io.github.mikai233.asteria.script.ScriptExecutionMetadata
import io.github.mikai233.asteria.script.ScriptTarget
import java.util.Base64

/**
 * HTTP request for submitting a GM script job.
 *
 * Script bodies are carried as base64 so the API can support text scripts, compiled scripts, and binary sidecar data
 * without relying on a JSON string escaping convention.
 */
data class GmScriptSubmitRequest(
    val executionId: String,
    val target: GmScriptTargetRequest,
    val artifact: GmScriptArtifactRequest,
    val metadata: GmScriptMetadataRequest = GmScriptMetadataRequest(),
    val timeoutMillis: Long = 3_000,
) {
    init {
        require(executionId.isNotBlank()) { "GM script execution id must not be blank" }
        require(timeoutMillis > 0) { "GM script timeout must be positive" }
    }

    fun toCommand(operatorId: String): ScriptExecutionCommand {
        return ScriptExecutionCommand(
            executionId = executionId,
            target = target.toScriptTarget(),
            artifact = artifact.toScriptArtifact(),
            metadata = ScriptExecutionMetadata(
                requester = operatorId,
                reason = metadata.reason,
                attributes = metadata.attributes,
            ),
        )
    }
}

/**
 * Runtime-neutral script target accepted by the GM HTTP API.
 */
data class GmScriptTargetRequest(
    val type: String,
    val role: String? = null,
    val addresses: List<String> = emptyList(),
    val paths: List<String> = emptyList(),
    val kind: String? = null,
    val ids: List<String> = emptyList(),
    val name: String? = null,
) {
    fun toScriptTarget(): ScriptTarget {
        return when (type) {
            "all-nodes" -> ScriptTarget.AllNodes
            "role" -> ScriptTarget.Role(RoleKey(requireValue(role, "role")))
            "nodes" -> ScriptTarget.Node(requireValues(addresses, "addresses"))
            "actor-paths" -> ScriptTarget.ActorPath(requireValues(paths, "paths"))
            "entity" -> ScriptTarget.Entity(
                kind = EntityKind(requireValue(kind, "kind")),
                ids = requireValues(ids, "ids"),
            )
            "singleton" -> ScriptTarget.Singleton(SingletonName(requireValue(name, "name")))
            else -> error("unsupported GM script target type $type")
        }
    }
}

/**
 * Script artifact accepted by the GM HTTP API.
 */
data class GmScriptArtifactRequest(
    val name: String,
    val engine: String,
    val bodyBase64: String,
    val extraBase64: String? = null,
    val checksum: String? = null,
) {
    init {
        require(name.isNotBlank()) { "GM script artifact name must not be blank" }
        require(engine.isNotBlank()) { "GM script artifact engine must not be blank" }
        require(bodyBase64.isNotBlank()) { "GM script artifact body must not be blank" }
        extraBase64?.let { require(it.isNotBlank()) { "GM script artifact extra must not be blank" } }
        checksum?.let { require(it.isNotBlank()) { "GM script artifact checksum must not be blank" } }
    }

    fun toScriptArtifact(): ScriptArtifact {
        return ScriptArtifact(
            name = name,
            engine = engine,
            body = Base64.getDecoder().decode(bodyBase64),
            extra = extraBase64?.let { Base64.getDecoder().decode(it) },
            checksum = checksum,
        )
    }
}

/**
 * Human and machine metadata attached to a submitted script job.
 */
data class GmScriptMetadataRequest(
    val reason: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        reason?.let { require(it.isNotBlank()) { "GM script reason must not be blank" } }
        attributes.forEach { (key, value) ->
            require(key.isNotBlank()) { "GM script metadata attribute key must not be blank" }
            require(value.isNotBlank()) { "GM script metadata attribute value must not be blank" }
        }
    }
}

private fun requireValue(value: String?, name: String): String {
    return value?.takeIf { it.isNotBlank() } ?: error("GM script target $name is required")
}

private fun requireValues(values: List<String>, name: String): List<String> {
    require(values.isNotEmpty()) { "GM script target $name must not be empty" }
    require(values.all { it.isNotBlank() }) { "GM script target $name must not contain blank values" }
    return values
}
