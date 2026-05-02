package io.github.mikai233.asteria.actor

import org.apache.pekko.actor.AbstractActor
import org.apache.pekko.actor.ActorRef
import kotlin.time.Duration

/**
 * Timer component for actors that want Pekko timer semantics without inheriting from `AbstractActorWithTimers`.
 *
 * Timer messages are delivered back to the owning actor, so they still go through the actor's current
 * receive/state-machine. Actors that need timers should own this component and call [start] from `preStart`.
 */
class ActorTimerSupport(
    private val owner: AbstractActor,
    private val childName: String = "timers",
) {
    private var timersActor: ActorRef? = null

    fun start() {
        check(timersActor == null) { "actor timer support has already been started" }
        timersActor = owner.context.actorOf(TimersActor.props(), childName)
    }

    fun cancelTimer(key: Any) {
        interact { it.cancel(key) }
    }

    fun cancelAllTimers() {
        interact { it.cancelAll() }
    }

    fun startSingleTimer(key: Any, message: Any, delay: Duration) {
        interact { it.startSingleTimer(key, message, delay) }
    }

    fun startTimerWithFixedDelay(key: Any, message: Any, delay: Duration) {
        interact { it.startTimerWithFixedDelay(key, message, delay) }
    }

    fun startTimerWithFixedDelay(key: Any, message: Any, initialDelay: Duration, delay: Duration) {
        interact { it.startTimerWithFixedDelay(key, message, initialDelay, delay) }
    }

    fun startTimerAtFixedRate(key: Any, message: Any, interval: Duration) {
        interact { it.startTimerAtFixedRate(key, message, interval) }
    }

    fun startTimerAtFixedRate(key: Any, message: Any, initialDelay: Duration, interval: Duration) {
        interact { it.startTimerAtFixedRate(key, message, initialDelay, interval) }
    }

    private fun interact(block: TimerInteractionBlock) {
        val actor = checkNotNull(timersActor) { "actor timer support must be started before using timers" }
        actor.tell(TimerInteraction(block), owner.self)
    }
}
