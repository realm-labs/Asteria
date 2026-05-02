package io.github.mikai233.asteria.gm.patch.spring

import io.github.mikai233.asteria.core.RoleKey
import io.github.mikai233.asteria.gm.patch.GmPatchCreateRequest
import io.github.mikai233.asteria.patch.PatchId
import io.github.mikai233.asteria.patch.PatchStatus
import io.github.mikai233.asteria.patch.PatchTarget
import io.github.mikai233.asteria.patch.RuntimePatchNodeResultQuery
import io.github.mikai233.asteria.patch.RuntimePatchNodeStatus
import io.github.mikai233.asteria.patch.RuntimePatchQuery

data class GmPatchListRequest(
    val status: PatchStatus? = null,
    val appName: String? = null,
    val version: String? = null,
) {
    fun toQuery(): RuntimePatchQuery {
        return RuntimePatchQuery(
            status = status,
            appName = appName,
            version = version,
        )
    }
}

data class GmPatchNodeResultListRequest(
    val patchId: String? = null,
    val address: String? = null,
    val status: RuntimePatchNodeStatus? = null,
) {
    fun toQuery(): RuntimePatchNodeResultQuery {
        return RuntimePatchNodeResultQuery(
            patchId = patchId?.let(::PatchId),
            address = address,
            status = status,
        )
    }
}

data class GmPatchCreateHttpRequest(
    val id: String,
    val name: String,
    val artifactName: String,
    val artifactVersion: String? = null,
    val appName: String,
    val versions: List<String>,
    val targetType: String = "all-nodes",
    val roles: List<String> = emptyList(),
    val addresses: List<String> = emptyList(),
    val priority: Int = 0,
    val status: PatchStatus = PatchStatus.Draft,
) {
    fun toRequest(): GmPatchCreateRequest {
        return GmPatchCreateRequest(
            id = PatchId(id),
            name = name,
            artifactName = artifactName,
            artifactVersion = artifactVersion,
            appName = appName,
            versions = versions.toSet(),
            target = target(),
            priority = priority,
            status = status,
        )
    }

    private fun target(): PatchTarget {
        return when (targetType.lowercase()) {
            "all-nodes" -> PatchTarget.AllNodes
            "roles" -> PatchTarget.Roles(roles.mapTo(linkedSetOf(), ::RoleKey))
            "nodes" -> PatchTarget.Nodes(addresses.toSet())
            else -> error("unsupported patch target type $targetType")
        }
    }
}
