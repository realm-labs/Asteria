package io.github.mikai233.asteria.actor

import io.github.mikai233.asteria.observability.*
import kotlinx.coroutines.future.await
import org.apache.pekko.actor.AbstractActor
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.event.Logging
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.pattern.Patterns
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

fun AbstractActor.actorLogger(): LoggingAdapter = Logging.getLogger(context.system, javaClass)

infix fun ActorRef.tell(message: Any) {
    tell(message, ActorRef.noSender())
}

suspend fun ActorRef.askAny(
    message: Any,
    timeout: Duration = 3.seconds,
): Any {
    return Patterns.ask(this, message, timeout.toJavaDuration()).await()
}

suspend inline fun <M : Any, reified R : Any> ActorRef.ask(
    message: M,
    timeout: Duration = 3.seconds,
    tracer: Tracer = NoopTracer,
    metrics: Metrics = NoopMetrics,
    spanName: String = "actor.ask",
): R {
    val tags = MetricTags.of("message" to message.javaClass.simpleName)
    val attributes = TraceAttributes.of(
        "actor.message" to message.javaClass.name,
        "actor.response" to R::class.qualifiedName.orEmpty(),
    )
    return tracer.span(spanName, attributes) {
        metrics.counter("asteria.actor.ask.total", tags).increment()
        metrics.timer("asteria.actor.ask.duration", tags).record {
            val response = askAny(message, timeout)
            response as? R
                ?: error("expected actor response ${R::class.qualifiedName}, got ${response::class.qualifiedName}")
        }
    }
}
