package io.github.mikai233.asteria.actor

import io.github.mikai233.asteria.core.NodeRuntime
import io.github.mikai233.asteria.core.NodeState
import io.github.mikai233.asteria.core.RoleKey
import io.github.mikai233.asteria.core.ServiceRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.Props
import scala.jdk.javaapi.FutureConverters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class ActorTimerSupportTest {
    @Test
    fun `timer support delivers timer messages to owner actor`() = runBlocking {
        val system = ActorSystem.create("actor-timer-support-${System.nanoTime()}")
        try {
            val actor = system.actorOf(TimerTestActor.props(TimerSupportTestRuntime()))

            assertEquals("started", actor.askAny(StartTimer))
            delay(100.milliseconds)

            assertEquals(true, actor.askAny(IsFired))
        } finally {
            FutureConverters.asJava(system.terminate()).await()
        }
    }
}

private object StartTimer

private object IsFired

private object TimerFired

private class TimerTestActor(
    runtime: TimerSupportTestRuntime,
) : AsteriaActor<TimerSupportTestRuntime>(runtime) {
    private val timers = ActorTimerSupport(this)
    private var fired = false

    override fun preStart() {
        super.preStart()
        timers.start()
    }

    override fun createReceive(): Receive {
        return receiveBuilder()
            .match(StartTimer::class.java) {
                timers.startSingleTimer("timer", TimerFired, 10.milliseconds)
                sender.tell("started", self)
            }
            .match(TimerFired::class.java) {
                fired = true
            }
            .match(IsFired::class.java) {
                sender.tell(fired, self)
            }
            .build()
    }

    companion object {
        fun props(runtime: TimerSupportTestRuntime): Props {
            return Props.create(TimerTestActor::class.java) {
                TimerTestActor(runtime)
            }
        }
    }
}

private class TimerSupportTestRuntime : NodeRuntime {
    override val name: String = "test"
    override val roles: Set<RoleKey> = emptySet()
    override val state: NodeState = NodeState.Started
    override val services: ServiceRegistry = ServiceRegistry()
}
