package io.github.realmlabs.asteria.actor

import io.github.realmlabs.asteria.core.NodeRuntime
import io.github.realmlabs.asteria.core.NodeState
import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.core.ServiceRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.Props
import scala.jdk.javaapi.FutureConverters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class ActorLifecycleGateTest {
    @Test
    fun `gate stashes messages until load completes`() = runBlocking {
        val system = ActorSystem.create("actor-lifecycle-load-${System.nanoTime()}")
        try {
            val loaded = CompletableDeferred<Unit>()
            val flushed = CompletableDeferred<Boolean>().also { it.complete(true) }
            val actor = system.actorOf(GatedActor.props(TestRuntime(), loaded, flushed))

            val response = async { actor.askAny("ping") }
            delay(100.milliseconds)

            assertEquals(false, response.isCompleted)
            loaded.complete(Unit)
            assertEquals("pong", response.await())
            assertEquals(ActorGracefulStopSucceeded, actor.askAny(ActorGracefulStop))
        } finally {
            FutureConverters.asJava(system.terminate()).await()
        }
    }

    @Test
    fun `gate waits for flush before graceful stop succeeds`() = runBlocking {
        val system = ActorSystem.create("actor-lifecycle-stop-${System.nanoTime()}")
        try {
            val loaded = CompletableDeferred<Unit>().also { it.complete(Unit) }
            val flushed = CompletableDeferred<Boolean>()
            val actor = system.actorOf(GatedActor.props(TestRuntime(), loaded, flushed))

            assertEquals("pong", actor.askAny("ping"))
            val stop = async { actor.askAny(ActorGracefulStop) }
            delay(100.milliseconds)

            assertEquals(false, stop.isCompleted)
            flushed.complete(true)
            assertEquals(ActorGracefulStopSucceeded, stop.await())
        } finally {
            FutureConverters.asJava(system.terminate()).await()
        }
    }
}

private class GatedActor(
    runtime: TestRuntime,
    private val loaded: CompletableDeferred<Unit>,
    private val flushed: CompletableDeferred<Boolean>,
) : AsteriaActor<TestRuntime>(runtime) {
    private val gate = ActorLifecycleGate(
        owner = this,
        load = { loaded.await() },
        flush = { flushed.await() },
    )

    override fun preStart() {
        super.preStart()
        gate.startLoading()
    }

    override fun createReceive(): Receive {
        return gate.loadingReceive(::runningReceive)
    }

    private fun runningReceive(): Receive {
        return receiveBuilder()
            .matchEquals("ping") {
                sender.tell("pong", self)
            }
            .match(ActorGracefulStop::class.java) {
                gate.beginStop(sender)
            }
            .build()
    }

    companion object {
        fun props(
            runtime: TestRuntime,
            loaded: CompletableDeferred<Unit>,
            flushed: CompletableDeferred<Boolean>,
        ): Props {
            return Props.create(GatedActor::class.java) {
                GatedActor(runtime, loaded, flushed)
            }
        }
    }
}

private class TestRuntime : NodeRuntime {
    override val name: String = "test"
    override val roles: Set<RoleKey> = emptySet()
    override val state: NodeState = NodeState.Started
    override val services: ServiceRegistry = ServiceRegistry()
}
