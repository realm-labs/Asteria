package io.github.realmlabs.asteria.message

import io.github.realmlabs.asteria.core.*
import io.github.realmlabs.asteria.observability.Counter
import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.Timer
import io.github.realmlabs.asteria.patch.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class MessageDispatcherTest {
    @Test
    fun dispatcherUsesCurrentRegistryHandle() = runBlocking {
        val events = mutableListOf<String>()
        val registry = PatchableMessageHandlerRegistry<HandlerContext, GameMessage>()
        registry.register(LoginHandler(events, "base"))
        val dispatcher = MessageDispatcher(registry)
        val runtime = PatchRuntime(TestRuntime)

        dispatcher.dispatch(context(), LoginReq("p1"))
        assertIs<PatchApplyResult.Applied>(
            runtime.apply(
                patch("login-fix"),
                plugin {
                    messageHandlers.replace(registry, LoginHandler(events, "patched"))
                },
            ),
        )
        dispatcher.dispatch(context(), LoginReq("p2"))

        assertEquals(listOf("base:p1", "patched:p2"), events)
    }

    @Test
    fun removingPatchRestoresPreviousHandlerLayer() = runBlocking {
        val events = mutableListOf<String>()
        val registry = PatchableMessageHandlerRegistry<HandlerContext, GameMessage>()
        registry.register(LoginHandler(events, "base"))
        val dispatcher = MessageDispatcher(registry)
        val runtime = PatchRuntime(TestRuntime)
        val first = patch("first", revision = 1)
        val second = patch("second", revision = 2)

        assertIs<PatchApplyResult.Applied>(
            runtime.apply(first, plugin { messageHandlers.replace(registry, LoginHandler(events, "first")) }),
        )
        assertIs<PatchApplyResult.Applied>(
            runtime.apply(second, plugin { messageHandlers.replace(registry, LoginHandler(events, "second")) }),
        )
        dispatcher.dispatch(context(), LoginReq("p1"))

        runtime.remove(second.id)
        dispatcher.dispatch(context(), LoginReq("p2"))

        runtime.remove(first.id)
        dispatcher.dispatch(context(), LoginReq("p3"))

        assertEquals(listOf("second:p1", "first:p2", "base:p3"), events)
    }

    @Test
    fun handlerCanDeclareSpecificContextType() {
        val events = mutableListOf<String>()
        val registry = PatchableMessageHandlerRegistry<EntityHandlerContext, GameMessage>()
        registry.register<EntityMessage> { context, message ->
            events += "${context.entityKind.value}:${context.entityId}:${message.value}"
        }
        val dispatcher = MessageDispatcher(registry)

        dispatcher.dispatch(
            DefaultEntityHandlerContext(TestRuntime, EntityKind("player"), "p1"),
            EntityMessage("loaded"),
        )

        assertEquals(listOf("player:p1:loaded"), events)
    }

    @Test
    fun dispatcherRecordsMessageMetrics() {
        val events = mutableListOf<String>()
        val metrics = RecordingMetrics()
        val runtime = TestRuntime.withMetrics(metrics)
        val registry = PatchableMessageHandlerRegistry<HandlerContext, GameMessage>()
        registry.register(LoginHandler(events, "base"))
        val dispatcher = MessageDispatcher(registry)

        dispatcher.dispatch(DefaultHandlerContext(runtime), LoginReq("p1"))

        val tags = mapOf(
            "runtime" to "test",
            "message" to LoginReq::class.qualifiedName.orEmpty(),
        )
        assertEquals(1, metrics.counter("asteria.message.dispatch.total", tags))
        assertEquals(0, metrics.counter("asteria.message.dispatch.failed.total", tags))
        assertEquals(1, metrics.timerCount("asteria.message.dispatch.duration", tags))
    }

    @Test
    fun dispatcherRecordsFailedMessageMetrics() {
        val metrics = RecordingMetrics()
        val runtime = TestRuntime.withMetrics(metrics)
        val registry = PatchableMessageHandlerRegistry<HandlerContext, GameMessage>()
        registry.register<LoginReq> { _, _ -> error("failed") }
        val dispatcher = MessageDispatcher(registry)

        assertFailsWith<IllegalStateException> {
            dispatcher.dispatch(DefaultHandlerContext(runtime), LoginReq("p1"))
        }

        val tags = mapOf(
            "runtime" to "test",
            "message" to LoginReq::class.qualifiedName.orEmpty(),
        )
        assertEquals(1, metrics.counter("asteria.message.dispatch.total", tags))
        assertEquals(1, metrics.counter("asteria.message.dispatch.failed.total", tags))
        assertEquals(1, metrics.timerCount("asteria.message.dispatch.duration", tags))
    }

    @Test
    fun actorDispatchBuildsActorContext() {
        val events = mutableListOf<String>()
        val registry = ActorMessageHandlerRegistry<TestActor, GameMessage>()
        registry.register<ActorMessage> { context, message ->
            events += "${context.runtime.name}:${context.actor.id}:${message.value}"
        }
        val dispatcher = MessageDispatcher(registry)

        dispatcher.dispatchActor(TestRuntime, TestActor("a1"), ActorMessage("hello"))
        dispatcher.dispatchActor(TestRuntime, TestActor("a2"), ActorMessage::class, ActorMessage("world"))

        assertEquals(
            listOf("test:a1:hello", "test:a2:world"),
            events,
        )
    }

    private fun context(): HandlerContext {
        return DefaultHandlerContext(TestRuntime)
    }

    private fun patch(
        id: String,
        revision: Long = 1,
    ): RuntimePatch {
        return RuntimePatch(PatchId(id), revision)
    }

    private fun plugin(block: RuntimePatchInstallContext.() -> Unit): RuntimePatchPlugin {
        return object : RuntimePatchPlugin {
            override suspend fun install(context: RuntimePatchInstallContext) {
                context.block()
            }
        }
    }

    private object TestRuntime : NodeRuntime {
        override val name: String = "test"
        override val roles: Set<RoleKey> = emptySet()
        override val state: NodeState = NodeState.Started
        override val services: ServiceRegistry = ServiceRegistry()

        fun withMetrics(metrics: Metrics): NodeRuntime {
            return object : NodeRuntime {
                override val name: String = this@TestRuntime.name
                override val roles: Set<RoleKey> = this@TestRuntime.roles
                override val state: NodeState = this@TestRuntime.state
                override val services: ServiceRegistry = ServiceRegistry().also {
                    it.register(Metrics::class, metrics)
                }
            }
        }
    }

    private sealed interface GameMessage

    private data class LoginReq(
        val playerId: String,
    ) : GameMessage

    private data class EntityMessage(
        val value: String,
    ) : GameMessage

    private data class ActorMessage(
        val value: String,
    ) : GameMessage

    private data class TestActor(
        val id: String,
    )

    private class LoginHandler(
        private val events: MutableList<String>,
        private val name: String,
    ) : MessageHandler<HandlerContext, LoginReq> {
        override fun handle(context: HandlerContext, message: LoginReq) {
            events += "$name:${message.playerId}"
        }
    }

    private class RecordingMetrics : Metrics {
        private val counters: MutableMap<MetricKey, Long> = linkedMapOf()
        private val timers: MutableMap<MetricKey, MutableList<Long>> = linkedMapOf()

        override fun counter(name: String, tags: MetricTags): Counter {
            val key = MetricKey(name, tags.asMap())
            return object : Counter {
                override fun increment(amount: Long) {
                    counters[key] = counter(name, tags.asMap()) + amount
                }
            }
        }

        override fun timer(name: String, tags: MetricTags): Timer {
            val key = MetricKey(name, tags.asMap())
            return object : Timer {
                override suspend fun <T> record(block: suspend () -> T): T {
                    return block()
                }

                override fun record(durationMillis: Long) {
                    timers.getOrPut(key) { mutableListOf() } += durationMillis
                }
            }
        }

        override fun gauge(name: String, tags: MetricTags, value: () -> Double) {
        }

        fun counter(name: String, tags: Map<String, String>): Long {
            return counters[MetricKey(name, tags)] ?: 0
        }

        fun timerCount(name: String, tags: Map<String, String>): Int {
            return timers[MetricKey(name, tags)]?.size ?: 0
        }
    }

    private data class MetricKey(
        val name: String,
        val tags: Map<String, String>,
    )
}
