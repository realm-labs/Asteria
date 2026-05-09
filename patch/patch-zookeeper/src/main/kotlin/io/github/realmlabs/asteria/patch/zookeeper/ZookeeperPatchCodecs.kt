package io.github.realmlabs.asteria.patch.zookeeper

import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.patch.*
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.Base64

internal fun RuntimePatch.encodeZnode(): ByteArray {
    return linkedMapOf(
        "id" to id.value,
        "name" to name,
        "artifactName" to artifact.name,
        "artifactChecksum" to artifact.checksum,
        "artifactVersion" to artifact.version,
        "appName" to compatibility.appName,
        "versions" to compatibility.versions.joinEncoded(),
        "targetType" to target.typeName(),
        "targetValues" to target.values().joinEncoded(),
        "priority" to priority.toString(),
        "sequence" to sequence.toString(),
        "status" to status.name,
    ).encodeFields()
}

internal fun ByteArray.decodeRuntimePatch(): RuntimePatch {
    val fields = decodeFields()
    val targetType = fields.required("targetType")
    val targetValues = fields.required("targetValues").splitEncoded()
    return RuntimePatch(
        id = PatchId(fields.required("id")),
        name = fields.required("name"),
        artifact = PatchArtifact(
            name = fields.required("artifactName"),
            checksum = fields.required("artifactChecksum"),
            version = fields["artifactVersion"]?.takeIf { it.isNotBlank() },
        ),
        compatibility = PatchCompatibility(
            appName = fields.required("appName"),
            versions = fields.required("versions").splitEncoded().toSet(),
        ),
        target = when (targetType) {
            "all-nodes" -> PatchTarget.AllNodes
            "roles" -> PatchTarget.Roles(targetValues.mapTo(linkedSetOf(), ::RoleKey))
            "nodes" -> PatchTarget.Nodes(targetValues.toSet())
            else -> error("unknown zookeeper patch target type $targetType")
        },
        priority = fields.required("priority").toInt(),
        sequence = fields.required("sequence").toLong(),
        status = PatchStatus.valueOf(fields.required("status")),
    )
}

internal fun RuntimePatchNodeResult.encodeZnode(): ByteArray {
    return linkedMapOf(
        "patchId" to patchId.value,
        "nodeId" to nodeId,
        "address" to address,
        "appName" to appName,
        "version" to version,
        "roles" to roles.map { it.value }.joinEncoded(),
        "status" to status.name,
        "attempt" to attempt.toString(),
        "operationCount" to operationCount?.toString(),
        "message" to message,
        "updatedAt" to updatedAt.toEpochMilli().toString(),
    ).encodeFields()
}

internal fun ByteArray.decodeRuntimePatchNodeResult(): RuntimePatchNodeResult {
    val fields = decodeFields()
    return RuntimePatchNodeResult(
        patchId = PatchId(fields.required("patchId")),
        nodeId = fields["nodeId"]?.takeIf { it.isNotBlank() },
        address = fields.required("address"),
        appName = fields.required("appName"),
        version = fields.required("version"),
        roles = fields.required("roles").splitEncoded().mapTo(linkedSetOf(), ::RoleKey),
        status = RuntimePatchNodeStatus.valueOf(fields.required("status")),
        attempt = fields.required("attempt").toInt(),
        operationCount = fields["operationCount"]?.takeIf { it.isNotBlank() }?.toInt(),
        message = fields["message"]?.takeIf { it.isNotBlank() },
        updatedAt = Instant.ofEpochMilli(fields.required("updatedAt").toLong()),
    )
}

internal fun PatchArtifact.encodeZnode(): ByteArray {
    return linkedMapOf(
        "name" to name,
        "checksum" to checksum,
        "version" to version,
    ).encodeFields()
}

internal fun ByteArray.decodePatchArtifact(): PatchArtifact {
    val fields = decodeFields()
    return PatchArtifact(
        name = fields.required("name"),
        checksum = fields.required("checksum"),
        version = fields["version"]?.takeIf { it.isNotBlank() },
    )
}

internal fun encodePatchIndex(
    appName: String,
    versions: Set<String>,
): ByteArray {
    return linkedMapOf(
        "appName" to appName,
        "versions" to versions.joinEncoded(),
    ).encodeFields()
}

internal fun ByteArray.decodePatchIndex(): PatchIndex {
    val fields = decodeFields()
    return PatchIndex(
        appName = fields.required("appName"),
        versions = fields.required("versions").splitEncoded().toSet(),
    )
}

internal data class PatchIndex(
    val appName: String,
    val versions: Set<String>,
)

private fun PatchTarget.typeName(): String {
    return when (this) {
        PatchTarget.AllNodes -> "all-nodes"
        is PatchTarget.Roles -> "roles"
        is PatchTarget.Nodes -> "nodes"
    }
}

private fun PatchTarget.values(): List<String> {
    return when (this) {
        PatchTarget.AllNodes -> emptyList()
        is PatchTarget.Roles -> roles.map { it.value }
        is PatchTarget.Nodes -> addresses.toList()
    }
}

private fun Map<String, String?>.encodeFields(): ByteArray {
    return entries.joinToString("\n") { (key, value) ->
        "$key=${(value ?: "").encodeField()}"
    }.toByteArray(UTF_8)
}

private fun ByteArray.decodeFields(): Map<String, String> {
    return toString(UTF_8)
        .lineSequence()
        .filter { it.isNotBlank() }
        .associate { line ->
            val separator = line.indexOf('=')
            require(separator > 0) { "invalid zookeeper patch field line" }
            line.substring(0, separator) to line.substring(separator + 1).decodeField()
        }
}

private fun Map<String, String>.required(key: String): String {
    return requireNotNull(this[key]) { "missing zookeeper patch field $key" }
}

private fun Iterable<String>.joinEncoded(): String {
    return joinToString(",") { it.encodeField() }
}

private fun String.splitEncoded(): List<String> {
    if (isBlank()) return emptyList()
    return split(",").map { it.decodeField() }
}

private fun String.encodeField(): String {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(UTF_8))
}

private fun String.decodeField(): String {
    if (isBlank()) return ""
    return String(Base64.getUrlDecoder().decode(this), UTF_8)
}
