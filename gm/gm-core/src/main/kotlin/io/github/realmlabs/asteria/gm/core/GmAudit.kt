package io.github.realmlabs.asteria.gm.core

import java.time.Instant
import java.util.*

/**
 * Audit record for a GM operation.
 */
data class GmAuditEvent(
    val id: String = UUID.randomUUID().toString(),
    val occurredAt: Instant = Instant.now(),
    val operatorId: String?,
    val operation: GmOperation,
    val request: GmRequestContext = GmRequestContext(),
    val success: Boolean,
    val message: String? = null,
) {
    init {
        require(id.isNotBlank()) { "GM audit id must not be blank" }
        operatorId?.let { require(it.isNotBlank()) { "GM audit operator id must not be blank" } }
        message?.let { require(it.isNotBlank()) { "GM audit message must not be blank" } }
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
