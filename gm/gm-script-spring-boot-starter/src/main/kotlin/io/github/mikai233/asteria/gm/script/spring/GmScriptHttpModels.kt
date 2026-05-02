package io.github.mikai233.asteria.gm.script.spring

import io.github.mikai233.asteria.core.EntityKind
import io.github.mikai233.asteria.core.RoleKey
import io.github.mikai233.asteria.core.SingletonName
import io.github.mikai233.asteria.script.*
import io.github.mikai233.asteria.script.job.ScriptJobExecutionAttributes
import io.github.mikai233.asteria.script.job.ScriptJobRetryFailedItemsRequest
import java.util.*

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
    val options: GmScriptExecutionOptionsRequest = GmScriptExecutionOptionsRequest(),
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
                attributes = metadata.attributes + options.toMetadataAttributes(),
                resources = metadata.resources.map { it.toScriptResourceRef() },
            ),
        )
    }
}

/**
 * Execution controls selected from the GM surface for one submitted job.
 */
data class GmScriptExecutionOptionsRequest(
    val maxConcurrentItems: Int? = null,
) {
    init {
        maxConcurrentItems?.let { require(it > 0) { "GM script max concurrent items must be positive" } }
    }

    fun toMetadataAttributes(): Map<String, String> {
        return buildMap {
            maxConcurrentItems?.let { put(ScriptJobExecutionAttributes.MaxConcurrentItems, it.toString()) }
        }
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
    val resources: List<GmScriptResourceRequest> = emptyList(),
) {
    init {
        reason?.let { require(it.isNotBlank()) { "GM script reason must not be blank" } }
        attributes.forEach { (key, value) ->
            require(key.isNotBlank()) { "GM script metadata attribute key must not be blank" }
            require(value.isNotBlank()) { "GM script metadata attribute value must not be blank" }
        }
        resources.map { it.name }.let { names ->
            require(names.distinct().size == names.size) { "GM script resource names must be unique" }
        }
    }
}

/**
 * External resource reference attached to a submitted script job.
 */
data class GmScriptResourceRequest(
    val name: String,
    val uri: String,
    val checksum: String? = null,
    val format: String? = null,
    val sizeBytes: Long? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(name.isNotBlank()) { "GM script resource name must not be blank" }
        require(uri.isNotBlank()) { "GM script resource uri must not be blank" }
        checksum?.let { require(it.isNotBlank()) { "GM script resource checksum must not be blank" } }
        format?.let { require(it.isNotBlank()) { "GM script resource format must not be blank" } }
        sizeBytes?.let { require(it >= 0) { "GM script resource size must not be negative" } }
        attributes.forEach { (key, value) ->
            require(key.isNotBlank()) { "GM script resource attribute key must not be blank" }
            require(value.isNotBlank()) { "GM script resource attribute value must not be blank" }
        }
    }

    fun toScriptResourceRef(): ScriptResourceRef {
        return ScriptResourceRef(
            name = name,
            uri = uri,
            checksum = checksum,
            format = format,
            sizeBytes = sizeBytes,
            attributes = attributes,
        )
    }
}

/**
 * HTTP request for retrying one failed script job item.
 */
data class GmScriptRetryItemRequest(
    val timeoutMillis: Long = 3_000,
) {
    init {
        require(timeoutMillis > 0) { "GM script retry timeout must be positive" }
    }
}

/**
 * HTTP request for retrying failed script job items that match an error bucket.
 */
data class GmScriptRetryFailedItemsRequest(
    val error: String? = null,
    val limit: Int = 100,
    val timeoutMillis: Long = 3_000,
) {
    init {
        error?.let { require(it.isNotBlank()) { "GM script retry error must not be blank" } }
        require(limit > 0) { "GM script retry limit must be positive" }
        require(timeoutMillis > 0) { "GM script retry timeout must be positive" }
    }

    fun toRetryRequest(): ScriptJobRetryFailedItemsRequest {
        return ScriptJobRetryFailedItemsRequest(error = error, limit = limit)
    }
}

/**
 * HTTP request for cancelling a script job or item.
 */
data class GmScriptCancelRequest(
    val reason: String? = null,
) {
    init {
        reason?.let { require(it.isNotBlank()) { "GM script cancel reason must not be blank" } }
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
