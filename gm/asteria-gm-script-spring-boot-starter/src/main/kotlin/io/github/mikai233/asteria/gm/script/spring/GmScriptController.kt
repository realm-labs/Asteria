package io.github.mikai233.asteria.gm.script.spring

import io.github.mikai233.asteria.gm.script.GmScriptOperations
import io.github.mikai233.asteria.gm.script.GmScriptPermissions
import io.github.mikai233.asteria.gm.script.GmScriptTargetValidationRequest
import io.github.mikai233.asteria.gm.script.GmScriptTargetValidationResult
import io.github.mikai233.asteria.gm.script.GmScriptTargetValidator
import io.github.mikai233.asteria.gm.spring.GmEndpointSupport
import io.github.mikai233.asteria.script.job.ScriptJob
import io.github.mikai233.asteria.script.job.ScriptJobId
import io.github.mikai233.asteria.script.job.ScriptJobItem
import io.github.mikai233.asteria.script.job.ScriptJobItemId
import io.github.mikai233.asteria.script.job.ScriptJobItemStatus
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
            when (val validation = validator.validate(GmScriptTargetValidationRequest(command, operation.principal.id))) {
                GmScriptTargetValidationResult.Allowed -> Unit
                is GmScriptTargetValidationResult.Rejected -> throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    validation.reasons.joinToString("; "),
                )
            }
            scripts.submit(
                command = command,
                timeout = request.timeoutMillis.milliseconds,
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

    @GetMapping("/jobs/{jobId}/items")
    suspend fun listItems(
        servletRequest: HttpServletRequest,
        @PathVariable jobId: String,
        @RequestParam status: ScriptJobItemStatus? = null,
    ): ResponseEntity<List<ScriptJobItem>> {
        return endpoints.execute(
            request = servletRequest,
            permission = GmScriptPermissions.Read,
            action = "gm.script.items.list",
            attributes = mapOf("jobId" to jobId),
        ) {
            val id = ScriptJobId(jobId)
            scripts.find(id) ?: return@execute ResponseEntity.notFound().build()
            ResponseEntity.ok(scripts.listItems(id, status))
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
        ) {
            scripts.retryItem(
                jobId = ScriptJobId(jobId),
                itemId = ScriptJobItemId(itemId),
                timeout = retryRequest.timeoutMillis.milliseconds,
            )
        }
    }
}
