package io.github.realmlabs.asteria.patch.message

import io.github.realmlabs.asteria.core.NodeRuntime
import io.github.realmlabs.asteria.core.NodeState
import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.core.ServiceRegistry
import io.github.realmlabs.asteria.message.DefaultHandlerContext
import io.github.realmlabs.asteria.message.HandlerContext
import io.github.realmlabs.asteria.message.MessageDispatcher
import io.github.realmlabs.asteria.message.MessageHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HotswapMessageHandlerRegistryTest {
    @Test
    fun `dispatcher uses directly replaced handler`() {
        val events = mutableListOf<String>()
        val registry = HotswapMessageHandlerRegistry<HandlerContext, GameMessage>()
        registry.register(LoginHandler(events, "base"))
        val dispatcher = MessageDispatcher(registry)

        dispatcher.dispatch(context(), LoginReq("p1"))
        val previous = registry.replace(LoginHandler(events, "hotswap"))
        dispatcher.dispatch(context(), LoginReq("p2"))

        assertNotNull(previous)
        assertEquals(listOf("base:p1", "hotswap:p2"), events)
    }

    @Test
    fun `replace rejects missing handler slot`() {
        val registry = HotswapMessageHandlerRegistry<HandlerContext, GameMessage>()

        assertFailsWith<IllegalStateException> {
            registry.replace(LoginHandler(mutableListOf(), "missing"))
        }
    }

    @Test
    fun `remove deletes handler slot`() {
        val events = mutableListOf<String>()
        val registry = HotswapMessageHandlerRegistry<HandlerContext, GameMessage>()
        registry.register(LoginHandler(events, "base"))

        assertNotNull(registry.remove<LoginReq>())
        assertNull(registry.get(LoginReq::class))
    }

    private fun context(): HandlerContext {
        return DefaultHandlerContext(TestRuntime)
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

    private class LoginHandler(
        private val events: MutableList<String>,
        private val name: String,
    ) : MessageHandler<HandlerContext, LoginReq> {
        override fun handle(context: HandlerContext, message: LoginReq) {
            events += "$name:${message.playerId}"
        }
    }
}
