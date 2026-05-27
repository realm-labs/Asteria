package io.github.realmlabs.asteria.message

import io.github.realmlabs.asteria.core.*
import io.github.realmlabs.asteria.observability.Counter
import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.Timer
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MessageDispatcherTest {
    @Test
    fun dispatcherUsesCurrentRegistryHandle() {
        val events = mutableListOf<String>()
        val registry = TestMessageHandleRegistry<HandlerContext, GameMessage>()
        registry.register(LoginHandler(events, "base"))
        val dispatcher = MessageDispatcher(registry)

        dispatcher.dispatch(context(), LoginReq("p1"))
        registry.register(LoginHandler(events, "replaced"))
        dispatcher.dispatch(context(), LoginReq("p2"))

        assertEquals(listOf("base:p1", "replaced:p2"), events)
    }

    @Test
    fun handlerCanDeclareSpecificContextType() {
        val events = mutableListOf<String>()
        val registry = TestMessageHandleRegistry<EntityHandlerContext, GameMessage>()
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
        val registry = TestMessageHandleRegistry<HandlerContext, GameMessage>()
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
        val registry = TestMessageHandleRegistry<HandlerContext, GameMessage>()
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
        val registry = TestMessageHandleRegistry<ActorHandlerContext<TestActor>, GameMessage>()
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

    private class TestMessageHandleRegistry<C : HandlerContext, M : Any>(
        handles: Iterable<MessageHandle<C, M>> = emptyList(),
    ) : MessageHandleRegistry<C, M> {
        private val handlesByMessageType = linkedMapOf<KClass<out M>, MessageHandle<C, M>>()

        init {
            handles.forEach(::register)
        }

        override fun get(messageType: KClass<out M>): MessageHandle<C, M>? {
            return handlesByMessageType[messageType]
        }

        override fun all(): Collection<MessageHandle<C, M>> {
            return handlesByMessageType.values
        }

        fun register(handle: MessageHandle<C, M>) {
            handlesByMessageType[handle.messageType] = handle
        }

        fun <T : M> register(
            messageType: KClass<T>,
            handler: MessageHandler<C, T>,
        ) {
            register(MessageHandle.of(messageType, handler))
        }

        inline fun <reified T : M> register(handler: MessageHandler<C, T>) {
            register(T::class, handler)
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
