package io.github.realmlabs.asteria.ops.http.ktor

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.realmlabs.asteria.script.ScriptArtifact
import io.github.realmlabs.asteria.script.ScriptExecutionCommand
import io.github.realmlabs.asteria.script.ScriptExecutionMetadata
import io.github.realmlabs.asteria.script.control.ScriptTargetRequest
import io.github.realmlabs.asteria.script.control.toScriptTarget
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.utils.io.*
import java.util.*

data class ReceivedOpsScriptCommand(
    val command: ScriptExecutionCommand,
    val timeoutMillis: Long,
    val targetType: String,
    val artifactName: String,
    val artifactEngine: String,
)

suspend fun ApplicationCall.receiveOpsScriptCommand(
    principal: NodeLocalOpsPrincipal,
    maxScriptBytes: Int,
): ReceivedOpsScriptCommand {
    return if (request.contentType().match(ContentType.MultiPart.FormData)) {
        receiveMultipartOpsScriptCommand(principal, maxScriptBytes)
    } else {
        val request = receive<OpsScriptExecutionRequest>()
        val command = request.toCommand(principal, maxScriptBytes)
        ReceivedOpsScriptCommand(
            command = command,
            timeoutMillis = request.timeoutMillis,
            targetType = request.target.type,
            artifactName = command.artifact.name,
            artifactEngine = command.artifact.engine,
        )
    }
}

private suspend fun ApplicationCall.receiveMultipartOpsScriptCommand(
    principal: NodeLocalOpsPrincipal,
    maxScriptBytes: Int,
): ReceivedOpsScriptCommand {
    var executionId: String? = null
    var target: ScriptTargetRequest? = null
    var artifactName: String? = null
    var engine: String? = null
    var artifactBytes: ByteArray? = null
    var metadata: OpsScriptMetadataRequest = OpsScriptMetadataRequest()
    var options: OpsScriptExecutionOptionsRequest = OpsScriptExecutionOptionsRequest()
    var timeoutMillis: Long = 3_000

    receiveMultipart().forEachPart { part ->
        try {
            when (part) {
                is PartData.FormItem -> when (part.name) {
                    "executionId" -> executionId = part.value.takeIf { it.isNotBlank() }
                    "target" -> target = mapper.readValue(part.value)
                    "name" -> artifactName = part.value.takeIf { it.isNotBlank() }
                    "engine" -> engine = part.value.takeIf { it.isNotBlank() }
                    "metadata" -> metadata = mapper.readValue(part.value)
                    "options" -> options = mapper.readValue(part.value)
                    "timeoutMillis" -> timeoutMillis = part.value.toLong()
                }

                is PartData.FileItem -> if (part.name == "artifact") {
                    artifactName = artifactName ?: part.originalFileName?.takeIf { it.isNotBlank() }
                    engine = engine ?: artifactName?.inferScriptEngine()
                    artifactBytes = part.provider().toByteArray()
                    require(artifactBytes.size <= maxScriptBytes) {
                        "script artifact body exceeds $maxScriptBytes bytes"
                    }
                }

                else -> Unit
            }
        } finally {
            part.dispose()
        }
    }

    val resolvedTarget = requireNotNull(target) { "multipart field target is required" }
    val resolvedBytes = requireNotNull(artifactBytes) { "multipart file field artifact is required" }
    val resolvedEngine = engine ?: error("multipart field engine is required when artifact file extension is not groovy or jar")
    require(timeoutMillis > 0) { "script timeout must be positive" }
    val resolvedName = artifactName ?: "ops-script"
    val attributes = metadata.attributes + options.toMetadataAttributes() + principal.toMetadataAttributes()
    val command = ScriptExecutionCommand(
        executionId = executionId ?: "ops-${UUID.randomUUID()}",
        target = resolvedTarget.toScriptTarget(),
        artifact = ScriptArtifact(
            name = resolvedName,
            engine = resolvedEngine,
            body = resolvedBytes,
        ),
        metadata = ScriptExecutionMetadata(
            requester = principal.id,
            reason = metadata.reason ?: principal.reason,
            attributes = attributes,
            resources = metadata.resources.map { it.toScriptResourceRef() },
        ),
    )
    return ReceivedOpsScriptCommand(
        command = command,
        timeoutMillis = timeoutMillis,
        targetType = resolvedTarget.type,
        artifactName = resolvedName,
        artifactEngine = resolvedEngine,
    )
}

private val mapper = jacksonObjectMapper()

private fun String.inferScriptEngine(): String? {
    return when (substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
        "groovy" -> "groovy"
        "jar" -> "jar"
        else -> null
    }
}
