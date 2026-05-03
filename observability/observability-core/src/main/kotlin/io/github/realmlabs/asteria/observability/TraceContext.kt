package io.github.realmlabs.asteria.observability

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

data class TraceMessage<T : Any>(
    val payload: T,
    val context: TraceContext,
)
