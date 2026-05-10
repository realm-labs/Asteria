package io.github.realmlabs.asteria.gm.patch.spring

import io.github.realmlabs.asteria.gm.core.GmOperation
import io.github.realmlabs.asteria.gm.core.GmResource
import io.github.realmlabs.asteria.gm.core.GmRiskLevel
import io.github.realmlabs.asteria.gm.patch.GmPatchActions
import io.github.realmlabs.asteria.gm.patch.GmPatchOperations
import io.github.realmlabs.asteria.gm.spring.GmEndpointSupport
import io.github.realmlabs.asteria.patch.*
import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
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
        @RequestParam status: PatchStatus = PatchStatus.Draft,
    ): RuntimePatchDescriptor {
        return endpoints.execute(
            request = request,
            operation = GmOperation(
                action = GmPatchActions.Create,
                resource = GmResource("patch", id),
                risk = GmRiskLevel.High,
                attributes = mapOf(
                    "name" to name,
                    "appName" to appName,
                    "targetType" to targetType,
                    "status" to status.name,
                ),
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
    ): List<RuntimePatchDescriptor> {
        return endpoints.execute(
            request = request,
            operation = GmOperation(
                action = GmPatchActions.Read,
                resource = GmResource("patches"),
                attributes = buildMap {
                    status?.let { put("status", it.name) }
                    appName?.let { put("appName", it) }
                    version?.let { put("version", it) }
                },
            ),
        ) {
            patches.list(GmPatchListRequest(status, appName, version).toQuery())
        }
    }

    @GetMapping("/{patchId}")
    suspend fun find(
        request: HttpServletRequest,
        @PathVariable patchId: String,
    ): ResponseEntity<RuntimePatchDescriptor> {
        return endpoints.execute(
            request = request,
            operation = GmOperation(GmPatchActions.Read, GmResource("patch", patchId)),
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
            operation = GmOperation(
                action = GmPatchActions.Read,
                resource = GmResource("patch.node-results"),
                attributes = buildMap {
                    patchId?.let { put("patchId", it) }
                    address?.let { put("address", it) }
                    status?.let { put("status", it.name) }
                },
            ),
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
            operation = GmOperation(
                action = GmPatchActions.Apply,
                resource = GmResource("patch", patchId),
                risk = GmRiskLevel.High,
            ),
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
            operation = GmOperation(
                action = GmPatchActions.Apply,
                resource = GmResource("patches.enabled"),
                risk = GmRiskLevel.High,
            ),
        ) {
            patches.applyEnabled()
        }
    }

    @PostMapping("/expire-incompatible")
    suspend fun expireIncompatible(
        request: HttpServletRequest,
    ): List<RuntimePatchDescriptor> {
        return endpoints.execute(
            request = request,
            operation = GmOperation(
                action = GmPatchActions.Expire,
                resource = GmResource("patches.incompatible"),
                risk = GmRiskLevel.High,
            ),
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
            operation = GmOperation(
                action = GmPatchActions.Disable,
                resource = GmResource("patch", patchId),
                risk = GmRiskLevel.High,
            ),
        ) {
            if (patches.disable(PatchId(patchId))) {
                ResponseEntity.accepted().build()
            } else {
                ResponseEntity.notFound().build()
            }
        }
    }
}
