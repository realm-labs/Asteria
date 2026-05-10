package io.github.realmlabs.asteria.gm.script.spring

import io.github.realmlabs.asteria.gm.script.*
import io.github.realmlabs.asteria.gm.spring.GmEndpointSupport
import io.github.realmlabs.asteria.script.ScriptExecutionCommand
import io.github.realmlabs.asteria.script.job.*
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import kotlin.time.Duration.Companion.milliseconds

/**
 * HTTP API for script GM tools.
 */
@RestController
@RequestMapping($$"${asteria.gm.api-prefix:/gm/api}/scripts")
class GmScriptController(
    private val scripts: GmScriptOperations,
    private val validator: GmScriptTargetValidator,
    private val endpoints: GmEndpointSupport,
    private val metadataProvider: GmScriptMetadataProvider,
    private val routeRegistry: GmScriptRouteRegistryView,
) {
    @GetMapping("/metadata")
    suspend fun metadata(
        servletRequest: HttpServletRequest,
    ): ResponseEntity<GmScriptMetadata> {
        return endpoints.execute(
            request = servletRequest,
            permission = GmScriptPermissions.Read,
            action = "gm.script.metadata",
        ) {
            ResponseEntity.ok(metadataProvider.metadata())
        }
    }

    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun submit(
        servletRequest: HttpServletRequest,
        @RequestBody request: GmScriptSubmitRequest,
    ): ScriptJob {
        return endpoints.execute(
            request = servletRequest,
            permission = GmScriptPermissions.Execute,
            action = "gm.script.submit",
            attributes = mapOf(
                "executionId" to request.executionId,
                "targetType" to request.target.type,
                "scriptName" to request.artifact.name,
                "scriptEngine" to request.artifact.engine,
            ),
        ) { operation ->
            val command = request.toCommand(operation.principal.id)
            validateRoute(command)
            validateTarget(command, operation.principal.id)
            scripts.submit(
                command = command,
                timeout = request.timeoutMillis.milliseconds,
            )
        }
    }

    @GetMapping("/jobs")
    suspend fun listJobs(
        servletRequest: HttpServletRequest,
        @RequestParam status: ScriptJobStatus? = null,
        @RequestParam requester: String? = null,
        @RequestParam offset: Int = 0,
        @RequestParam limit: Int = 100,
    ): ResponseEntity<ScriptJobPage> {
        return endpoints.execute(
            request = servletRequest,
            permission = GmScriptPermissions.Read,
            action = "gm.script.jobs.list",
            attributes = buildMap {
                status?.let { put("status", it.name) }
                requester?.let { put("requester", it) }
            },
        ) {
            ResponseEntity.ok(
                scripts.listJobs(
                    ScriptJobQuery(
                        status = status,
                        requester = requester,
                        offset = offset,
                        limit = limit,
                    ),
                ),
            )
        }
    }

    @GetMapping("/jobs/{jobId}")
    suspend fun find(
        servletRequest: HttpServletRequest,
        @PathVariable jobId: String,
    ): ResponseEntity<ScriptJob> {
        return endpoints.execute(
            request = servletRequest,
            permission = GmScriptPermissions.Read,
            action = "gm.script.find",
            attributes = mapOf("jobId" to jobId),
        ) {
            scripts.find(ScriptJobId(jobId))
                ?.let { ResponseEntity.ok(it) }
                ?: ResponseEntity.notFound().build()
        }
    }

    @GetMapping("/jobs/{jobId}/summary")
    suspend fun summarizeResults(
        servletRequest: HttpServletRequest,
        @PathVariable jobId: String,
    ): ResponseEntity<Any> {
        return endpoints.execute(
            request = servletRequest,
            permission = GmScriptPermissions.Read,
            action = "gm.script.results.summary",
            attributes = mapOf("jobId" to jobId),
        ) {
            val id = ScriptJobId(jobId)
            scripts.find(id) ?: return@execute ResponseEntity.notFound().build()
            ResponseEntity.ok(scripts.summarizeResults(id))
        }
    }

    @GetMapping("/jobs/{jobId}/results.csv")
    suspend fun exportResults(
        servletRequest: HttpServletRequest,
        @PathVariable jobId: String,
        @RequestParam status: ScriptJobItemStatus? = null,
    ): ResponseEntity<String> {
        return endpoints.execute(
            request = servletRequest,
            permission = GmScriptPermissions.Read,
            action = "gm.script.results.export",
            attributes = buildMap {
                put("jobId", jobId)
                status?.let { put("status", it.name) }
            },
        ) {
            val id = ScriptJobId(jobId)
            scripts.find(id) ?: return@execute ResponseEntity.notFound().build()
            val export = scripts.exportResults(id, status)
            ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(export.contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${export.fileName}\"")
                .body(export.content)
        }
    }

    @PostMapping("/jobs/{jobId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun cancelJob(
        servletRequest: HttpServletRequest,
        @PathVariable jobId: String,
        @RequestBody(required = false) request: GmScriptCancelRequest?,
    ): ScriptJob {
        val cancelRequest = request ?: GmScriptCancelRequest()
        return endpoints.execute(
            request = servletRequest,
            permission = GmScriptPermissions.Cancel,
            action = "gm.script.cancel",
            attributes = mapOf("jobId" to jobId),
        ) { operation ->
            scripts.cancelJob(
                jobId = ScriptJobId(jobId),
                cancellation = ScriptJobCancellation(
                    requestedBy = operation.principal.id,
                    reason = cancelRequest.reason,
                ),
            ) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "script job $jobId not found")
        }
    }

    @GetMapping("/jobs/{jobId}/items")
    suspend fun listItems(
        servletRequest: HttpServletRequest,
        @PathVariable jobId: String,
        @RequestParam status: ScriptJobItemStatus? = null,
        @RequestParam offset: Int = 0,
        @RequestParam limit: Int = 100,
    ): ResponseEntity<ScriptJobItemPage> {
        return endpoints.execute(
            request = servletRequest,
            permission = GmScriptPermissions.Read,
            action = "gm.script.items.list",
            attributes = mapOf("jobId" to jobId),
        ) {
            val id = ScriptJobId(jobId)
            scripts.find(id) ?: return@execute ResponseEntity.notFound().build()
            ResponseEntity.ok(
                scripts.listItems(
                    id,
                    ScriptJobItemQuery(status = status, offset = offset, limit = limit)
                )
            )
        }
    }

    @PostMapping("/jobs/{jobId}/items/{itemId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun cancelItem(
        servletRequest: HttpServletRequest,
        @PathVariable jobId: String,
        @PathVariable itemId: String,
        @RequestBody(required = false) request: GmScriptCancelRequest?,
    ): ScriptJobItem {
        val cancelRequest = request ?: GmScriptCancelRequest()
        return endpoints.execute(
            request = servletRequest,
            permission = GmScriptPermissions.Cancel,
            action = "gm.script.items.cancel",
            attributes = mapOf(
                "jobId" to jobId,
                "itemId" to itemId,
            ),
        ) { operation ->
            scripts.cancelItem(
                jobId = ScriptJobId(jobId),
                itemId = ScriptJobItemId(itemId),
                cancellation = ScriptJobCancellation(
                    requestedBy = operation.principal.id,
                    reason = cancelRequest.reason,
                ),
            ) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "script job item $itemId not found")
        }
    }

    @GetMapping("/jobs/{jobId}/items/{itemId}")
    suspend fun findItem(
        servletRequest: HttpServletRequest,
        @PathVariable jobId: String,
        @PathVariable itemId: String,
    ): ResponseEntity<ScriptJobItem> {
        return endpoints.execute(
            request = servletRequest,
            permission = GmScriptPermissions.Read,
            action = "gm.script.items.find",
            attributes = mapOf(
                "jobId" to jobId,
                "itemId" to itemId,
            ),
        ) {
            scripts.findItem(ScriptJobId(jobId), ScriptJobItemId(itemId))
                ?.let { ResponseEntity.ok(it) }
                ?: ResponseEntity.notFound().build()
        }
    }

    @PostMapping("/jobs/{jobId}/items/{itemId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun retryItem(
        servletRequest: HttpServletRequest,
        @PathVariable jobId: String,
        @PathVariable itemId: String,
        @RequestBody(required = false) request: GmScriptRetryItemRequest?,
    ): ScriptJobItem {
        val retryRequest = request ?: GmScriptRetryItemRequest()
        return endpoints.execute(
            request = servletRequest,
            permission = GmScriptPermissions.Execute,
            action = "gm.script.items.retry",
            attributes = mapOf(
                "jobId" to jobId,
                "itemId" to itemId,
            ),
        ) { operation ->
            val id = ScriptJobId(jobId)
            val item = scripts.findItem(id, ScriptJobItemId(itemId))
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "script job item $itemId not found")
            val job = scripts.find(id)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "script job $jobId not found")
            val command = job.command.copy(target = item.target)
            validateRoute(command)
            validateTarget(command, operation.principal.id)
            scripts.retryItem(
                jobId = id,
                itemId = ScriptJobItemId(itemId),
                timeout = retryRequest.timeoutMillis.milliseconds,
                requestedBy = operation.principal.id,
            )
        }
    }

    @PostMapping("/jobs/{jobId}/failed-items/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun retryFailedItems(
        servletRequest: HttpServletRequest,
        @PathVariable jobId: String,
        @RequestBody(required = false) request: GmScriptRetryFailedItemsRequest?,
    ): List<ScriptJobItem> {
        val retryRequest = request ?: GmScriptRetryFailedItemsRequest()
        return endpoints.execute(
            request = servletRequest,
            permission = GmScriptPermissions.Execute,
            action = "gm.script.failed-items.retry",
            attributes = buildMap {
                put("jobId", jobId)
                retryRequest.error?.let { put("error", it) }
                put("limit", retryRequest.limit.toString())
            },
        ) { operation ->
            val id = ScriptJobId(jobId)
            scripts.find(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "script job $jobId not found")
            scripts.retryFailedItems(
                jobId = id,
                request = retryRequest.toRetryRequest(),
                timeout = retryRequest.timeoutMillis.milliseconds,
                requestedBy = operation.principal.id,
            )
        }
    }

    private suspend fun validateTarget(command: ScriptExecutionCommand, operatorId: String) {
        when (val validation = validator.validate(GmScriptTargetValidationRequest(command, operatorId))) {
            GmScriptTargetValidationResult.Allowed -> Unit
            is GmScriptTargetValidationResult.Rejected -> throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                validation.reasons.joinToString("; "),
            )
        }
    }

    private fun validateRoute(command: ScriptExecutionCommand) {
        val reasons = routeRegistry.validate(command.target)
        if (reasons.isNotEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, reasons.joinToString("; "))
        }
    }
}
