package io.github.realmlabs.asteria.script.pekko

import io.github.realmlabs.asteria.actor.ActorLifecycleGate
import io.github.realmlabs.asteria.actor.AsteriaActor
import io.github.realmlabs.asteria.actor.askAny
import io.github.realmlabs.asteria.core.NodeRuntime
import io.github.realmlabs.asteria.core.NodeState
import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.core.ServiceRegistry
import io.github.realmlabs.asteria.script.*
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

class ActorScriptSupportTest {
    @Test
    fun `script support can be composed into a normal asteria actor`() = runBlocking {
        val runtime = TestRuntime()
        runtime.services.register(
            ScriptRunner::class,
            ScriptRunner(
                executor = ScriptExecutor(ScriptEngineRegistry(listOf(ComponentScriptEngine))),
                policy = DefaultScriptPolicy(allowActorScripts = true),
            ),
        )
        val system = ActorSystem.create("actor-script-support-${System.nanoTime()}")
        try {
            val actor = system.actorOf(ComponentScriptActor.props(runtime))
            val command = ExecuteActorScript(
                executionId = "actor-script",
                artifact = ScriptArtifact("component", ComponentScriptEngine.name, ByteArray(0)),
                target = ScriptTarget.ActorPath(listOf("/user/component")),
            )

            val result = actor.askAny(command) as ScriptExecutionResult

            assertEquals("actor-script", result.executionId)
            assertEquals(true, result.success)
            assertEquals("/user/component", result.target)
            assertEquals("pong", actor.askAny("ping"))
        } finally {
            FutureConverters.asJava(system.terminate()).await()
        }
    }

    @Test
    fun `script support does not bypass lifecycle gate loading state`() = runBlocking {
        val runtime = scriptRuntime()
        val loaded = CompletableDeferred<Unit>()
        val system = ActorSystem.create("actor-script-lifecycle-${System.nanoTime()}")
        try {
            val actor = system.actorOf(LifecycleScriptActor.props(runtime, loaded))
            val command = ExecuteActorScript(
                executionId = "actor-script-gated",
                artifact = ScriptArtifact("component", ComponentScriptEngine.name, ByteArray(0)),
                target = ScriptTarget.ActorPath(listOf("/user/component")),
            )

            val result = async { actor.askAny(command) as ScriptExecutionResult }
            delay(100.milliseconds)
            assertEquals(false, result.isCompleted)
            loaded.complete(Unit)

            assertEquals("actor-script-gated", result.await().executionId)
        } finally {
            FutureConverters.asJava(system.terminate()).await()
        }
    }
}

private fun scriptRuntime(): TestRuntime {
    return TestRuntime().also { runtime ->
        runtime.services.register(
            ScriptRunner::class,
            ScriptRunner(
                executor = ScriptExecutor(ScriptEngineRegistry(listOf(ComponentScriptEngine))),
                policy = DefaultScriptPolicy(allowActorScripts = true),
            ),
        )
    }
}

private object ComponentScriptEngine : ScriptEngine {
    override val name: String = "component"

    override fun compile(artifact: ScriptArtifact): CompiledScript {
        return CompiledScript { }
    }
}

private class ComponentScriptActor(
    runtime: TestRuntime,
) : AsteriaActor<TestRuntime>(runtime) {
    private val scripts = ActorScriptSupport(this)

    override fun createReceive(): Receive {
        val business = receiveBuilder()
            .matchEquals("ping") {
                sender.tell("pong", self)
            }
            .build()
        return business.orElse(scripts.receive())
    }

    companion object {
        fun props(runtime: TestRuntime): Props {
            return Props.create(ComponentScriptActor::class.java) {
                ComponentScriptActor(runtime)
            }
        }
    }
}

private class LifecycleScriptActor(
    runtime: TestRuntime,
    loaded: CompletableDeferred<Unit>,
) : AsteriaActor<TestRuntime>(runtime) {
    private val gate = ActorLifecycleGate(
        owner = this,
        load = { loaded.await() },
    )
    private val scripts = ActorScriptSupport(this)

    override fun preStart() {
        super.preStart()
        gate.startLoading()
    }

    override fun createReceive(): Receive {
        return gate.loadingReceive(::runningReceive)
    }

    private fun runningReceive(): Receive {
        return scripts.receive()
    }

    companion object {
        fun props(runtime: TestRuntime, loaded: CompletableDeferred<Unit>): Props {
            return Props.create(LifecycleScriptActor::class.java) {
                LifecycleScriptActor(runtime, loaded)
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
