package io.github.mikai233.asteria.actor

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
): R {
    val response = askAny(message, timeout)
    return response as? R
        ?: error("expected actor response ${R::class.qualifiedName}, got ${response::class.qualifiedName}")
}
