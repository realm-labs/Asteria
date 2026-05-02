package io.github.mikai233.asteria.actor

import org.apache.pekko.actor.AbstractActorWithTimers
import org.apache.pekko.actor.Props
import org.apache.pekko.actor.TimerScheduler
import kotlin.time.Duration
import kotlin.time.toJavaDuration

typealias TimerInteractionBlock = (TimerScheduler) -> Unit

data class TimerInteraction(val block: TimerInteractionBlock)

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
