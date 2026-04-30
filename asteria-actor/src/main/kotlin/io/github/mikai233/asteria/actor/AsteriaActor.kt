package io.github.mikai233.asteria.actor

import io.github.mikai233.asteria.core.NodeRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
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
    val coroutineScope: TrackingCoroutineScope = self.safeActorCoroutineScope()

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
        when (msg) {
            is ActorCoroutineRunnable -> runCatching { msg.run() }
                .onFailure { logger.error(it, "{} failed to run actor coroutine", self) }

            is NamedActorTask -> runCatching { msg.block() }
                .onFailure { logger.error(it, "{} failed to run task {}", self, msg.name) }

            else -> super.aroundReceive(receive, msg)
        }
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
    ) = coroutineScope.trackedLaunch(context, start) {
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
        return coroutineScope.async(context, start, block)
    }
}

data class NamedActorTask(
    val name: String,
    val block: () -> Unit,
)
