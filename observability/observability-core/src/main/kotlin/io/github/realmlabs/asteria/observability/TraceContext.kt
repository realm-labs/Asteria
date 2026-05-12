package io.github.realmlabs.asteria.observability

/**
 * Transport-neutral trace identifiers carried across Asteria boundaries.
 *
 * A context is considered usable for propagation only when both [traceId] and [spanId] are present. [baggage] is kept
 * as string metadata so adapters can map it to their native propagation format without depending on a specific tracing
 * implementation.
 */
data class TraceContext(
    val traceId: String? = null,
    val spanId: String? = null,
    val baggage: Map<String, String> = emptyMap(),
) {
    init {
        traceId?.let { require(it.isNotBlank()) { "trace id must not be blank" } }
        spanId?.let { require(it.isNotBlank()) { "span id must not be blank" } }
        baggage.forEach { (key, _) -> require(key.isNotBlank()) { "baggage key must not be blank" } }
    }

    val isValid: Boolean get() = traceId != null && spanId != null
}

/**
 * Immutable string attributes attached to spans and trace events.
 *
 * Attribute keys must be non-blank. When two sets are combined with [plus], values from the right-hand side replace
 * values with the same key from the left-hand side.
 */
data class TraceAttributes(
    private val values: Map<String, String> = emptyMap(),
) {
    init {
        values.forEach { (key, _) -> require(key.isNotBlank()) { "trace attribute key must not be blank" } }
    }

    fun asMap(): Map<String, String> = values

    operator fun plus(other: TraceAttributes): TraceAttributes {
        return TraceAttributes(values + other.values)
    }

    companion object {
        val Empty = TraceAttributes()

        fun of(vararg pairs: Pair<String, String>): TraceAttributes {
            return TraceAttributes(mapOf(*pairs))
        }
    }
}

/**
 * Payload wrapper used when a message must retain trace context while crossing a queue, actor, or transport boundary.
 */
data class TraceMessage<T : Any>(
    val payload: T,
    val context: TraceContext,
)
