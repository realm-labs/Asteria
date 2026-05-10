package io.github.realmlabs.asteria.message

import io.github.realmlabs.asteria.core.*
import io.github.realmlabs.asteria.patch.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
