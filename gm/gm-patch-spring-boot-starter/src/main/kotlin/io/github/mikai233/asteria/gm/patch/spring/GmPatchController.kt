package io.github.mikai233.asteria.gm.patch.spring

import io.github.mikai233.asteria.gm.patch.GmPatchOperations
import io.github.mikai233.asteria.gm.patch.GmPatchPermissions
import io.github.mikai233.asteria.gm.spring.GmEndpointSupport
import io.github.mikai233.asteria.patch.PatchClusterApplyResult
import io.github.mikai233.asteria.patch.PatchId
import io.github.mikai233.asteria.patch.PatchStatus
import io.github.mikai233.asteria.patch.RuntimePatchNodeResult
import io.github.mikai233.asteria.patch.RuntimePatchNodeStatus
import io.github.mikai233.asteria.patch.RuntimePatch
import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping($$"${asteria.gm.api-prefix:/gm/api}/patches")
class GmPatchController(
    private val patches: GmPatchOperations,
    private val endpoints: GmEndpointSupport,
) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        request: HttpServletRequest,
        @RequestParam file: MultipartFile,
        @RequestParam id: String,
        @RequestParam name: String,
        @RequestParam appName: String,
        @RequestParam versions: List<String>,
        @RequestParam artifactName: String? = null,
        @RequestParam artifactVersion: String? = null,
        @RequestParam targetType: String = "all-nodes",
        @RequestParam roles: List<String> = emptyList(),
        @RequestParam addresses: List<String> = emptyList(),
        @RequestParam priority: Int = 0,
        @RequestParam status: PatchStatus = PatchStatus.Draft,
    ): RuntimePatch {
        return endpoints.execute(
            request = request,
            permission = GmPatchPermissions.Create,
            action = "gm.patch.create",
            attributes = mapOf(
                "patchId" to id,
                "name" to name,
                "appName" to appName,
                "targetType" to targetType,
                "status" to status.name,
            ),
        ) {
            val bytes = withContext(Dispatchers.IO) {
                file.bytes
            }
            patches.create(
                GmPatchCreateHttpRequest(
                    id = id,
                    name = name,
                    artifactName = artifactName ?: file.originalFilename ?: "$id.jar",
                    artifactVersion = artifactVersion,
                    appName = appName,
                    versions = versions,
                    targetType = targetType,
                    roles = roles,
                    addresses = addresses,
                    priority = priority,
                    status = status,
                ).toRequest(),
                bytes,
            )
        }
    }

    @GetMapping
    suspend fun list(
        request: HttpServletRequest,
        @RequestParam status: PatchStatus? = null,
        @RequestParam appName: String? = null,
        @RequestParam version: String? = null,
    ): List<RuntimePatch> {
        return endpoints.execute(
            request = request,
            permission = GmPatchPermissions.Read,
            action = "gm.patch.list",
            attributes = buildMap {
                status?.let { put("status", it.name) }
                appName?.let { put("appName", it) }
                version?.let { put("version", it) }
            },
        ) {
            patches.list(GmPatchListRequest(status, appName, version).toQuery())
        }
    }

    @GetMapping("/{patchId}")
    suspend fun find(
        request: HttpServletRequest,
        @PathVariable patchId: String,
    ): ResponseEntity<RuntimePatch> {
        return endpoints.execute(
            request = request,
            permission = GmPatchPermissions.Read,
            action = "gm.patch.find",
            attributes = mapOf("patchId" to patchId),
        ) {
            patches.find(PatchId(patchId))
                ?.let { ResponseEntity.ok(it) }
                ?: ResponseEntity.notFound().build()
        }
    }

    @GetMapping("/node-results")
    suspend fun nodeResults(
        request: HttpServletRequest,
        @RequestParam patchId: String? = null,
        @RequestParam address: String? = null,
        @RequestParam status: RuntimePatchNodeStatus? = null,
    ): List<RuntimePatchNodeResult> {
        return endpoints.execute(
            request = request,
            permission = GmPatchPermissions.Read,
            action = "gm.patch.node-results",
            attributes = buildMap {
                patchId?.let { put("patchId", it) }
                address?.let { put("address", it) }
                status?.let { put("status", it.name) }
            },
        ) {
            patches.nodeResults(GmPatchNodeResultListRequest(patchId, address, status).toQuery())
        }
    }

    @PostMapping("/{patchId}/apply")
    suspend fun apply(
        request: HttpServletRequest,
        @PathVariable patchId: String,
    ): PatchClusterApplyResult {
        return endpoints.execute(
            request = request,
            permission = GmPatchPermissions.Apply,
            action = "gm.patch.apply",
            attributes = mapOf("patchId" to patchId),
        ) {
            patches.apply(PatchId(patchId))
        }
    }

    @PostMapping("/apply-enabled")
    suspend fun applyEnabled(
        request: HttpServletRequest,
    ): List<PatchClusterApplyResult> {
        return endpoints.execute(
            request = request,
            permission = GmPatchPermissions.Apply,
            action = "gm.patch.apply-enabled",
        ) {
            patches.applyEnabled()
        }
    }

    @PostMapping("/expire-incompatible")
    suspend fun expireIncompatible(
        request: HttpServletRequest,
    ): List<RuntimePatch> {
        return endpoints.execute(
            request = request,
            permission = GmPatchPermissions.Expire,
            action = "gm.patch.expire-incompatible",
        ) {
            patches.expireIncompatible()
        }
    }

    @PostMapping("/{patchId}/disable")
    suspend fun disable(
        request: HttpServletRequest,
        @PathVariable patchId: String,
    ): ResponseEntity<Unit> {
        return endpoints.execute(
            request = request,
            permission = GmPatchPermissions.Disable,
            action = "gm.patch.disable",
            attributes = mapOf("patchId" to patchId),
        ) {
            if (patches.disable(PatchId(patchId))) {
                ResponseEntity.accepted().build()
            } else {
                ResponseEntity.notFound().build()
            }
        }
    }
}
