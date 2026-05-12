package io.github.realmlabs.asteria.actor

import org.apache.pekko.actor.AbstractActorWithTimers
import org.apache.pekko.actor.Props
import org.apache.pekko.actor.TimerScheduler
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Callback applied to a Pekko [TimerScheduler] inside the timer actor.
 */
typealias TimerInteractionBlock = (TimerScheduler) -> Unit

/**
 * Message used by [ActorTimerSupport] to mutate timer state on the timer actor.
 */
data class TimerInteraction(val block: TimerInteractionBlock)

/**
 * Child actor that owns a Pekko timer scheduler and forwards fired timer messages to its parent.
 */
class TimersActor : AbstractActorWithTimers() {
    companion object {
        fun props(): Props = Props.create(TimersActor::class.java)
    }

    override fun createReceive(): Receive {
        return receiveBuilder()
            .match(TimerInteraction::class.java) { it.block(timers) }
            .matchAny { context.parent.tell(it, self) }
            .build()
    }
}

fun TimerScheduler.startSingleTimer(key: Any, message: Any, delay: Duration) {
    startSingleTimer(key, message, delay.toJavaDuration())
}

fun TimerScheduler.startTimerWithFixedDelay(key: Any, message: Any, delay: Duration) {
    startTimerWithFixedDelay(key, message, delay.toJavaDuration())
}

fun TimerScheduler.startTimerWithFixedDelay(key: Any, message: Any, initialDelay: Duration, delay: Duration) {
    startTimerWithFixedDelay(key, message, initialDelay.toJavaDuration(), delay.toJavaDuration())
}

fun TimerScheduler.startTimerAtFixedRate(key: Any, message: Any, interval: Duration) {
    startTimerAtFixedRate(key, message, interval.toJavaDuration())
}

fun TimerScheduler.startTimerAtFixedRate(key: Any, message: Any, initialDelay: Duration, interval: Duration) {
    startTimerAtFixedRate(key, message, initialDelay.toJavaDuration(), interval.toJavaDuration())
}
