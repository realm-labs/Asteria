package io.github.mikai233.asteria.actor

import io.github.mikai233.asteria.core.NodeRuntime
import io.github.mikai233.asteria.observability.MetricTags
import io.github.mikai233.asteria.observability.Metrics
import io.github.mikai233.asteria.observability.NoopMetrics
import io.github.mikai233.asteria.observability.NoopTracer
import io.github.mikai233.asteria.observability.TraceAttributes
import io.github.mikai233.asteria.observability.Tracer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeout
import org.apache.pekko.actor.AbstractActorWithStash
import org.apache.pekko.actor.ActorRef
import scala.PartialFunction
import scala.runtime.BoxedUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

abstract class AsteriaActor<N : NodeRuntime>(
    val runtime: N,
) : AbstractActorWithStash() {
    val logger = actorLogger()
    val coroutineScope: ActorCoroutineScope = self.actorCoroutineScope()

    private lateinit var timersActor: ActorRef

    override fun preStart() {
        super.preStart()
        timersActor = context.actorOf(TimersActor.props(), "timers")
    }

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

    fun execute(name: String, block: () -> Unit) {
        self tell NamedActorTask(name, block)
    }

    fun cancelTimer(key: Any) {
        timersActor tell TimerInteraction { it.cancel(key) }
    }

    fun cancelAllTimers() {
        timersActor tell TimerInteraction { it.cancelAll() }
    }

    fun startSingleTimer(key: Any, message: Any, delay: Duration) {
        timersActor tell TimerInteraction { it.startSingleTimer(key, message, delay) }
    }

    fun startTimerWithFixedDelay(key: Any, message: Any, delay: Duration) {
        timersActor tell TimerInteraction { it.startTimerWithFixedDelay(key, message, delay) }
    }

    fun startTimerAtFixedRate(key: Any, message: Any, interval: Duration) {
        timersActor tell TimerInteraction { it.startTimerAtFixedRate(key, message, interval) }
    }

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

    fun <T> async(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> T,
    ): Deferred<T> {
        return coroutineScope.asyncTracked(context, start, block)
    }
}

data class NamedActorTask(
    val name: String,
    val block: () -> Unit,
)
