package io.github.mikai233.asteria.script.job

import io.github.mikai233.asteria.script.ScriptExecutionCommand
import io.github.mikai233.asteria.script.ScriptExecutionResult
import java.time.Instant
import java.util.*

enum class ScriptJobAuditEventType {
    JobSubmitted,
    JobResumed,
    JobCancelled,
    ItemStarted,
    ItemCompleted,
    ItemFailed,
    ItemCancelled,
    ItemExpired,
    ItemRetryRequested,
    ItemCancelRequested,
    ItemStaleFinishIgnored,
}

data class ScriptJobAuditEvent(
    val id: String = UUID.randomUUID().toString(),
    val occurredAt: Instant = Instant.now(),
    val type: ScriptJobAuditEventType,
    val jobId: ScriptJobId,
    val itemId: ScriptJobItemId? = null,
    val attempt: Int? = null,
    val workerId: String? = null,
    val status: ScriptJobItemStatus? = null,
    val operatorId: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(id.isNotBlank()) { "script job audit id must not be blank" }
        attempt?.let { require(it > 0) { "script job audit attempt must be positive" } }
        workerId?.let { require(it.isNotBlank()) { "script job audit worker id must not be blank" } }
        operatorId?.let { require(it.isNotBlank()) { "script job audit operator id must not be blank" } }
        attributes.forEach { (key, _) -> require(key.isNotBlank()) { "script job audit attribute key must not be blank" } }
    }
}

fun interface ScriptJobAuditSink {
    suspend fun record(event: ScriptJobAuditEvent)
}

object NoopScriptJobAuditSink : ScriptJobAuditSink {
    override suspend fun record(event: ScriptJobAuditEvent) = Unit
}

class CompositeScriptJobAuditSink(
    private val sinks: List<ScriptJobAuditSink>,
) : ScriptJobAuditSink {
    init {
        require(sinks.isNotEmpty()) { "script job audit sinks must not be empty" }
    }

    override suspend fun record(event: ScriptJobAuditEvent) {
        sinks.forEach { it.record(event) }
    }
}

internal fun ScriptExecutionCommand.auditAttributes(): Map<String, String> {
    return buildMap {
        put("executionId", executionId)
        put("scriptName", artifact.name)
        put("scriptEngine", artifact.engine)
        artifact.checksum?.let { put("scriptChecksum", it) }
        put("targetType", target.auditType())
        metadata.requester?.let { put("requester", it) }
        metadata.reason?.let { put("reason", it) }
        if (metadata.resources.isNotEmpty()) {
            put("resourceCount", metadata.resources.size.toString())
            put(
                "resourceChecksums",
                metadata.resources.joinToString(",") { resource ->
                    "${resource.name}=${resource.checksum ?: "none"}"
                },
            )
        }
    }
}

internal fun List<ScriptExecutionResult>.auditSummary(): Map<String, String> {
    val successCount = count { it.success }
    val failureCount = size - successCount
    return buildMap {
        put("resultCount", size.toString())
        put("successCount", successCount.toString())
        put("failureCount", failureCount.toString())
        firstOrNull { !it.success }?.error?.let { put("firstError", it) }
    }
}
