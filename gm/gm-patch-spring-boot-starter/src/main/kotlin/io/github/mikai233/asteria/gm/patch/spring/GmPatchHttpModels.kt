package io.github.mikai233.asteria.gm.patch.spring

import io.github.mikai233.asteria.patch.PatchStatus
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
