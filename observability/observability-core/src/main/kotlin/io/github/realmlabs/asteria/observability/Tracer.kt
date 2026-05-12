package io.github.realmlabs.asteria.observability

/**
 * Minimal tracing facade used by framework modules.
 *
 * [span] preserves context across coroutine suspension points when the implementation supports it. [spanBlocking] is
 * for synchronous actor or callback code where suspending is not possible. Implementations should record thrown errors
 * before rethrowing them so framework control flow is unchanged.
 */
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

/**
 * Mutable view of the span currently being executed.
 */
interface TraceScope {
    val context: TraceContext

    fun event(name: String, attributes: TraceAttributes = TraceAttributes.Empty)

    fun error(error: Throwable)
}

/**
 * Tracer implementation used when no tracing backend has been installed.
 */
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
