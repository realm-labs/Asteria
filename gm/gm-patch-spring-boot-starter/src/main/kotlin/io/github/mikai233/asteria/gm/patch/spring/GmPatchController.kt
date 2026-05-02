package io.github.mikai233.asteria.gm.patch.spring

import io.github.mikai233.asteria.gm.patch.GmPatchOperations
import io.github.mikai233.asteria.gm.patch.GmPatchPermissions
import io.github.mikai233.asteria.gm.spring.GmEndpointSupport
import io.github.mikai233.asteria.patch.PatchApplyResult
import io.github.mikai233.asteria.patch.PatchId
import io.github.mikai233.asteria.patch.PatchStatus
import io.github.mikai233.asteria.patch.RuntimePatch
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping($$"${asteria.gm.api-prefix:/gm/api}/patches")
class GmPatchController(
    private val patches: GmPatchOperations,
    private val endpoints: GmEndpointSupport,
) {
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

    @PostMapping("/{patchId}/apply")
    suspend fun apply(
        request: HttpServletRequest,
        @PathVariable patchId: String,
    ): PatchApplyResult {
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
    ): List<PatchApplyResult> {
        return endpoints.execute(
            request = request,
            permission = GmPatchPermissions.Apply,
            action = "gm.patch.apply-enabled",
        ) {
            patches.applyEnabled()
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
