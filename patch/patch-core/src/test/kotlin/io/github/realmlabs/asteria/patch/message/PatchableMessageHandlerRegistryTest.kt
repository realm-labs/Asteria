package io.github.realmlabs.asteria.patch.message

import io.github.realmlabs.asteria.core.NodeRuntime
import io.github.realmlabs.asteria.core.NodeState
import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.core.ServiceRegistry
import io.github.realmlabs.asteria.message.DefaultHandlerContext
import io.github.realmlabs.asteria.message.HandlerContext
import io.github.realmlabs.asteria.message.MessageDispatcher
import io.github.realmlabs.asteria.message.MessageHandler
import io.github.realmlabs.asteria.patch.PatchApplyResult
import io.github.realmlabs.asteria.patch.PatchId
import io.github.realmlabs.asteria.patch.PatchRuntime
import io.github.realmlabs.asteria.patch.RuntimePatch
import io.github.realmlabs.asteria.patch.RuntimePatchInstallContext
import io.github.realmlabs.asteria.patch.RuntimePatchPlugin
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PatchableMessageHandlerRegistryTest {
    @Test
    fun dispatcherUsesPatchedRegistryHandle() = runBlocking {
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

    private class LoginHandler(
        private val events: MutableList<String>,
        private val name: String,
    ) : MessageHandler<HandlerContext, LoginReq> {
        override fun handle(context: HandlerContext, message: LoginReq) {
            events += "$name:${message.playerId}"
        }
    }
}
