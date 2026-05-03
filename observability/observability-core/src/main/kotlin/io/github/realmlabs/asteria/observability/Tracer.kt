package io.github.realmlabs.asteria.observability

interface Tracer {
    suspend fun <T> span(
        name: String,
        attributes: TraceAttributes = TraceAttributes.Empty,
        block: suspend TraceScope.() -> T,
    ): T

    fun <T> spanBlocking(
        name: String,
        attributes: TraceAttributes = TraceAttributes.Empty,
        block: TraceScope.() -> T,
    ): T

    fun currentContext(): TraceContext
}

interface TraceScope {
    val context: TraceContext

    fun event(name: String, attributes: TraceAttributes = TraceAttributes.Empty)

    fun error(error: Throwable)
}

object NoopTracer : Tracer {
    private val context = TraceContext()

    override suspend fun <T> span(
        name: String,
        attributes: TraceAttributes,
        block: suspend TraceScope.() -> T,
    ): T {
        require(name.isNotBlank()) { "span name must not be blank" }
        return NoopTraceScope.block()
    }

    override fun <T> spanBlocking(
        name: String,
        attributes: TraceAttributes,
        block: TraceScope.() -> T,
    ): T {
        require(name.isNotBlank()) { "span name must not be blank" }
        return NoopTraceScope.block()
    }

    override fun currentContext(): TraceContext = context
}

object NoopTraceScope : TraceScope {
    override val context: TraceContext = TraceContext()

    override fun event(name: String, attributes: TraceAttributes) {
        require(name.isNotBlank()) { "trace event name must not be blank" }
    }

    override fun error(error: Throwable) {
    }
}
