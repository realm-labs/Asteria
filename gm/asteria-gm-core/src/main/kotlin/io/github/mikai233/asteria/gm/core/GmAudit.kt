package io.github.mikai233.asteria.gm.core

import java.time.Instant
import java.util.UUID

/**
 * Audit record for a GM operation.
 *
 * Applications can persist these events to a database, log pipeline, or security platform. High-risk permissions such
 * as script execution and actor diagnostics should always emit an audit event from the application-facing controller.
 */
data class GmAuditEvent(
    val id: String = UUID.randomUUID().toString(),
    val occurredAt: Instant = Instant.now(),
    val operatorId: String?,
    val permission: GmPermissionKey?,
    val action: String,
    val scope: GmResourceScope = GmResourceScope.Empty,
    val success: Boolean,
    val message: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(id.isNotBlank()) { "GM audit id must not be blank" }
        operatorId?.let { require(it.isNotBlank()) { "GM audit operator id must not be blank" } }
        require(action.isNotBlank()) { "GM audit action must not be blank" }
        message?.let { require(it.isNotBlank()) { "GM audit message must not be blank" } }
        attributes.keys.forEach { require(it.isNotBlank()) { "GM audit attribute key must not be blank" } }
    }
}

/**
 * Receives GM audit events.
 */
fun interface GmAuditSink {
    suspend fun record(event: GmAuditEvent)
}

/**
 * Audit sink used when the application has not installed persistence yet.
 */
object NoopGmAuditSink : GmAuditSink {
    override suspend fun record(event: GmAuditEvent) = Unit
}

/**
 * Fan-out sink for writing audit events to multiple destinations.
 */
class CompositeGmAuditSink(
    private val sinks: List<GmAuditSink>,
) : GmAuditSink {
    override suspend fun record(event: GmAuditEvent) {
        sinks.forEach { it.record(event) }
    }
}
