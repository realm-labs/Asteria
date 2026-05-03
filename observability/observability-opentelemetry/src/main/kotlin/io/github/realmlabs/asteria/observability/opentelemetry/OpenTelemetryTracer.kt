package io.github.realmlabs.asteria.observability.opentelemetry

import io.github.realmlabs.asteria.observability.TraceAttributes
import io.github.realmlabs.asteria.observability.TraceContext
import io.github.realmlabs.asteria.observability.TraceScope
import io.github.realmlabs.asteria.observability.Tracer
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.withContext
import io.opentelemetry.api.trace.Tracer as OpenTelemetryApiTracer

class OpenTelemetryTracer(
    private val tracer: OpenTelemetryApiTracer,
) : Tracer {
    override suspend fun <T> span(
        name: String,
        attributes: TraceAttributes,
        block: suspend TraceScope.() -> T,
    ): T {
        require(name.isNotBlank()) { "span name must not be blank" }
        val span = tracer.spanBuilder(name)
            .setAllAttributes(attributes.toOpenTelemetryAttributes())
            .startSpan()
        val context = Context.current().with(span)
        return try {
            withContext(context.asContextElement()) {
                OpenTelemetryTraceScope(span, context).block()
            }
        } catch (error: Throwable) {
            span.recordException(error)
            span.setStatus(StatusCode.ERROR)
            throw error
        } finally {
            span.end()
        }
    }

    override fun <T> spanBlocking(
        name: String,
        attributes: TraceAttributes,
        block: TraceScope.() -> T,
    ): T {
        require(name.isNotBlank()) { "span name must not be blank" }
        val span = tracer.spanBuilder(name)
            .setAllAttributes(attributes.toOpenTelemetryAttributes())
            .startSpan()
        val context = Context.current().with(span)
        return try {
            context.makeCurrent().use {
                OpenTelemetryTraceScope(span, context).block()
            }
        } catch (error: Throwable) {
            span.recordException(error)
            span.setStatus(StatusCode.ERROR)
            throw error
        } finally {
            span.end()
        }
    }

    override fun currentContext(): TraceContext {
        return Span.current().toTraceContext()
    }
}

private class OpenTelemetryTraceScope(
    private val span: Span,
    override val context: TraceContext,
) : TraceScope {
    constructor(span: Span, context: Context) : this(span, context.toTraceContext())

    override fun event(name: String, attributes: TraceAttributes) {
        require(name.isNotBlank()) { "trace event name must not be blank" }
        span.addEvent(name, attributes.toOpenTelemetryAttributes())
    }

    override fun error(error: Throwable) {
        span.recordException(error)
        span.setStatus(StatusCode.ERROR)
    }
}

private fun Context.toTraceContext(): TraceContext {
    return Span.fromContext(this).toTraceContext()
}

private fun Span.toTraceContext(): TraceContext {
    val spanContext = spanContext
    return if (spanContext.isValid) {
        TraceContext(
            traceId = spanContext.traceId,
            spanId = spanContext.spanId,
        )
    } else {
        TraceContext()
    }
}
