package io.github.realmlabs.asteria.ops.http.ktor

import io.github.realmlabs.asteria.core.EntityKind
import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.core.SingletonName
import io.github.realmlabs.asteria.script.*
import io.github.realmlabs.asteria.script.job.ScriptJobExecutionAttributes
import io.github.realmlabs.asteria.script.job.ScriptJobRetryFailedItemsRequest
import java.util.Base64
import java.util.UUID

data class OpsErrorResponse(
    val error: String,
)

data class OpsScriptExecutionRequest(
    val executionId: String? = null,
    val target: OpsScriptTargetRequest,
    val artifact: OpsScriptArtifactRequest,
    val metadata: OpsScriptMetadataRequest = OpsScriptMetadataRequest(),
    val options: OpsScriptExecutionOptionsRequest = OpsScriptExecutionOptionsRequest(),
    val timeoutMillis: Long = 3_000,
) {
    init {
        executionId?.let { require(it.isNotBlank()) { "script execution id must not be blank" } }
        require(timeoutMillis > 0) { "script timeout must be positive" }
    }

    fun toCommand(
        principal: NodeLocalOpsPrincipal,
        maxScriptBytes: Int,
    ): ScriptExecutionCommand {
        val artifact = artifact.toScriptArtifact(maxScriptBytes)
        return ScriptExecutionCommand(
            executionId = executionId ?: "ops-${UUID.randomUUID()}",
            target = target.toScriptTarget(),
            artifact = artifact,
            metadata = ScriptExecutionMetadata(
                requester = principal.id,
                reason = metadata.reason ?: principal.reason,
                attributes = metadata.attributes + options.toMetadataAttributes() + principal.toMetadataAttributes(),
                resources = metadata.resources.map { it.toScriptResourceRef() },
            ),
        )
    }
}

data class OpsScriptExecutionOptionsRequest(
    val maxConcurrentItems: Int? = null,
) {
    init {
        maxConcurrentItems?.let { require(it > 0) { "script max concurrent items must be positive" } }
    }

    fun toMetadataAttributes(): Map<String, String> {
        return buildMap {
            maxConcurrentItems?.let { put(ScriptJobExecutionAttributes.MaxConcurrentItems, it.toString()) }
        }
    }
}

data class OpsScriptTargetRequest(
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
            "entity" -> ScriptTarget.Entity(EntityKind(requireValue(kind, "kind")), requireValues(ids, "ids"))
            "singleton" -> ScriptTarget.Singleton(SingletonName(requireValue(name, "name")))
            else -> error("unsupported script target type $type")
        }
    }
}

data class OpsScriptArtifactRequest(
    val name: String = "ops-script",
    val engine: String,
    val bodyText: String? = null,
    val bodyBase64: String? = null,
    val extraBase64: String? = null,
    val checksum: String? = null,
) {
    init {
        require(name.isNotBlank()) { "script artifact name must not be blank" }
        require(engine.isNotBlank()) { "script artifact engine must not be blank" }
        require(!bodyText.isNullOrBlank() || !bodyBase64.isNullOrBlank()) {
            "script artifact bodyText or bodyBase64 must be configured"
        }
        extraBase64?.let { require(it.isNotBlank()) { "script artifact extra must not be blank" } }
        checksum?.let { require(it.isNotBlank()) { "script artifact checksum must not be blank" } }
    }

    fun toScriptArtifact(maxScriptBytes: Int): ScriptArtifact {
        val body = bodyBase64?.let { Base64.getDecoder().decode(it) } ?: bodyText.orEmpty().toByteArray()
        require(body.size <= maxScriptBytes) { "script artifact body exceeds $maxScriptBytes bytes" }
        val extra = extraBase64?.let { Base64.getDecoder().decode(it) }
        extra?.let { require(it.size <= maxScriptBytes) { "script artifact extra exceeds $maxScriptBytes bytes" } }
        return ScriptArtifact(name = name, engine = engine, body = body, extra = extra, checksum = checksum)
    }
}

data class OpsScriptMetadataRequest(
    val reason: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val resources: List<OpsScriptResourceRequest> = emptyList(),
) {
    init {
        reason?.let { require(it.isNotBlank()) { "script reason must not be blank" } }
        attributes.forEach { (key, value) ->
            require(key.isNotBlank()) { "script metadata attribute key must not be blank" }
            require(value.isNotBlank()) { "script metadata attribute value must not be blank" }
        }
        resources.map { it.name }.let { names ->
            require(names.distinct().size == names.size) { "script resource names must be unique" }
        }
    }
}

data class OpsScriptResourceRequest(
    val name: String,
    val uri: String,
    val checksum: String? = null,
    val format: String? = null,
    val sizeBytes: Long? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(name.isNotBlank()) { "script resource name must not be blank" }
        require(uri.isNotBlank()) { "script resource uri must not be blank" }
        checksum?.let { require(it.isNotBlank()) { "script resource checksum must not be blank" } }
        format?.let { require(it.isNotBlank()) { "script resource format must not be blank" } }
        sizeBytes?.let { require(it >= 0) { "script resource size must not be negative" } }
        attributes.forEach { (key, value) ->
            require(key.isNotBlank()) { "script resource attribute key must not be blank" }
            require(value.isNotBlank()) { "script resource attribute value must not be blank" }
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

data class OpsScriptCancelRequest(
    val reason: String? = null,
) {
    init {
        reason?.let { require(it.isNotBlank()) { "script cancel reason must not be blank" } }
    }
}

data class OpsScriptRetryItemRequest(
    val timeoutMillis: Long = 3_000,
) {
    init {
        require(timeoutMillis > 0) { "script retry timeout must be positive" }
    }
}

data class OpsScriptRetryFailedItemsRequest(
    val error: String? = null,
    val limit: Int = 100,
    val timeoutMillis: Long = 3_000,
) {
    init {
        error?.let { require(it.isNotBlank()) { "script retry error must not be blank" } }
        require(limit > 0) { "script retry limit must be positive" }
        require(timeoutMillis > 0) { "script retry timeout must be positive" }
    }

    fun toRetryRequest(): ScriptJobRetryFailedItemsRequest {
        return ScriptJobRetryFailedItemsRequest(error = error, limit = limit)
    }
}

fun NodeLocalOpsPrincipal.toMetadataAttributes(): Map<String, String> {
    return buildMap {
        put("ops.source", source)
        ticket?.let { put("ops.ticket", it) }
        attributes.forEach { (key, value) -> put("ops.$key", value) }
    }
}

private fun requireValue(value: String?, name: String): String {
    return value?.takeIf { it.isNotBlank() } ?: error("script target $name is required")
}

private fun requireValues(values: List<String>, name: String): List<String> {
    require(values.isNotEmpty()) { "script target $name must not be empty" }
    require(values.all { it.isNotBlank() }) { "script target $name must not contain blank values" }
    return values
}
