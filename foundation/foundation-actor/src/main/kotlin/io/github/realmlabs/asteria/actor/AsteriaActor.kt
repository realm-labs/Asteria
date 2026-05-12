package io.github.realmlabs.asteria.actor

import io.github.realmlabs.asteria.core.NodeRuntime
import io.github.realmlabs.asteria.observability.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeout
import org.apache.pekko.actor.AbstractActorWithStash
import scala.PartialFunction
import scala.runtime.BoxedUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Base actor that wires Asteria runtime services into Pekko actors.
 *
 * Incoming messages are wrapped with tracing and metrics, while coroutine tasks posted through [launch] or [async] are
 * executed on the actor mailbox. This keeps actor state access serialized as long as coroutine code resumes through the
 * provided [coroutineScope].
 */
abstract class AsteriaActor<N : NodeRuntime>(
    val runtime: N,
) : AbstractActorWithStash() {
    val logger = actorLogger()
    val coroutineScope: ActorCoroutineScope = self.actorCoroutineScope()

    override fun postStop() {
        coroutineScope.cancel()
        super.postStop()
    }

    override fun aroundReceive(receive: PartialFunction<Any, BoxedUnit>?, msg: Any?) {
        val tracer = runtime.services.find<Tracer>() ?: NoopTracer
        val metrics = runtime.services.find<Metrics>() ?: NoopMetrics
        val tags = MetricTags.of(
            "actor" to javaClass.simpleName,
            "message" to msg.messageName(),
        )
        val attributes = TraceAttributes.of(
            "actor.class" to javaClass.name,
            "actor.path" to self.path().toString(),
            "actor.message" to msg.messageClassName(),
        )
        metrics.counter("asteria.actor.receive.total", tags).increment()
        val startedAt = System.nanoTime()
        try {
            tracer.spanBlocking("actor.receive", attributes) {
                runCatching {
                    receiveMessage(receive, msg)
                }.onFailure {
                    error(it)
                    metrics.counter("asteria.actor.receive.failed.total", tags).increment()
                    throw it
                }
            }
        } finally {
            metrics.timer("asteria.actor.receive.duration", tags).record((System.nanoTime() - startedAt) / 1_000_000)
        }
    }

    private fun receiveMessage(receive: PartialFunction<Any, BoxedUnit>?, msg: Any?) {
        when (msg) {
            is ActorCoroutineTask -> runCatching { msg.run() }
                .onFailure { logger.error(it, "{} failed to run actor coroutine", self) }

            is NamedActorTask -> runCatching { msg.block() }
                .onFailure { logger.error(it, "{} failed to run task {}", self, msg.name) }

            else -> super.aroundReceive(receive, msg)
        }
    }

    private fun Any?.messageName(): String {
        return this?.javaClass?.simpleName ?: "null"
    }

    private fun Any?.messageClassName(): String {
        return this?.javaClass?.name ?: "null"
    }

    /**
     * Posts a named synchronous task to this actor's mailbox.
     */
    fun execute(name: String, block: () -> Unit) {
        self tell NamedActorTask(name, block)
    }

    /**
     * Launches coroutine work whose continuations resume through this actor's mailbox.
     *
     * The default timeout bounds accidental long-running actor work. Pass `timeout = null` for lifecycle operations that
     * intentionally wait on external resources.
     */
    fun launch(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        timeout: Duration? = 3.seconds,
        block: suspend CoroutineScope.() -> Unit,
    ) = coroutineScope.launchTracked(context, start) {
        if (timeout == null) {
            block()
        } else {
            withTimeout(timeout, block)
        }
    }

    /**
     * Starts coroutine work that returns a [Deferred] while keeping continuations on the actor mailbox.
     */
    fun <T> async(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> T,
    ): Deferred<T> {
        return coroutineScope.asyncTracked(context, start, block)
    }
}

/**
 * Mailbox task used by [AsteriaActor.execute].
 */
data class NamedActorTask(
    val name: String,
    val block: () -> Unit,
)
