package io.github.mikai233.asteria.gm.script.spring

import io.github.mikai233.asteria.gm.script.GmScriptOperations
import io.github.mikai233.asteria.gm.script.GmScriptPermissions
import io.github.mikai233.asteria.gm.script.GmScriptTargetValidationRequest
import io.github.mikai233.asteria.gm.script.GmScriptTargetValidationResult
import io.github.mikai233.asteria.gm.script.GmScriptTargetValidator
import io.github.mikai233.asteria.gm.spring.GmEndpointSupport
import io.github.mikai233.asteria.script.ScriptExecutionCommand
import io.github.mikai233.asteria.script.job.ScriptJobCancellation
import io.github.mikai233.asteria.script.job.ScriptJob
import io.github.mikai233.asteria.script.job.ScriptJobId
import io.github.mikai233.asteria.script.job.ScriptJobItem
import io.github.mikai233.asteria.script.job.ScriptJobItemId
import io.github.mikai233.asteria.script.job.ScriptJobItemPage
import io.github.mikai233.asteria.script.job.ScriptJobItemQuery
import io.github.mikai233.asteria.script.job.ScriptJobItemStatus
import io.github.mikai233.asteria.script.job.ScriptJobPage
import io.github.mikai233.asteria.script.job.ScriptJobQuery
import io.github.mikai233.asteria.script.job.ScriptJobStatus
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
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
) {
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
            ResponseEntity.ok(scripts.listItems(id, ScriptJobItemQuery(status = status, offset = offset, limit = limit)))
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
            validateTarget(job.command.copy(target = item.target), operation.principal.id)
            scripts.retryItem(
                jobId = id,
                itemId = ScriptJobItemId(itemId),
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
}
