package io.github.mikai233.asteria.gm.script.spring

import io.github.mikai233.asteria.gm.script.GmScriptOperations
import io.github.mikai233.asteria.gm.script.GmScriptPermissions
import io.github.mikai233.asteria.gm.script.GmScriptTargetValidationRequest
import io.github.mikai233.asteria.gm.script.GmScriptTargetValidationResult
import io.github.mikai233.asteria.gm.script.GmScriptTargetValidator
import io.github.mikai233.asteria.gm.spring.GmEndpointSupport
import io.github.mikai233.asteria.script.job.ScriptJob
import io.github.mikai233.asteria.script.job.ScriptJobId
import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
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
    fun submit(
        servletRequest: HttpServletRequest,
        @RequestBody request: GmScriptSubmitRequest,
    ): ScriptJob {
        return runBlocking {
            endpoints.execute(
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
    }

    @GetMapping("/jobs/{jobId}")
    fun find(
        servletRequest: HttpServletRequest,
        @PathVariable jobId: String,
    ): ResponseEntity<ScriptJob> {
        return runBlocking {
            endpoints.execute(
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
    }
}
