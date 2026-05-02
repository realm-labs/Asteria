package io.github.mikai233.asteria.message

import io.github.mikai233.asteria.core.NodeRuntime
import io.github.mikai233.asteria.core.NodeState
import io.github.mikai233.asteria.core.RoleKey
import io.github.mikai233.asteria.core.ServiceRegistry
import io.github.mikai233.asteria.patch.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MessageDispatcherTest {
    @Test
    fun dispatcherUsesCurrentRegistryHandle() = runBlocking {
        val events = mutableListOf<String>()
        val registry = PatchableMessageHandlerRegistry<GameMessage>()
        registry.register(LoginHandler(events, "base"))
        val dispatcher = MessageDispatcher(registry)
        val runtime = PatchRuntime(PatchEnvironment("game", "1.0.0"))

        dispatcher.dispatch(context(), LoginReq("p1"))
        assertIs<PatchApplyResult.Applied>(
            runtime.apply(
                patch("login-fix"),
                plugin {
                    replaceHandler(registry, LoginHandler(events, "patched"))
                },
            ),
        )
        dispatcher.dispatch(context(), LoginReq("p2"))

        assertEquals(listOf("base:p1", "patched:p2"), events)
    }

    @Test
    fun removingPatchRestoresPreviousHandlerLayer() = runBlocking {
        val events = mutableListOf<String>()
        val registry = PatchableMessageHandlerRegistry<GameMessage>()
        registry.register(LoginHandler(events, "base"))
        val dispatcher = MessageDispatcher(registry)
        val runtime = PatchRuntime(PatchEnvironment("game", "1.0.0"))
        val first = patch("first", sequence = 1)
        val second = patch("second", sequence = 2)

        assertIs<PatchApplyResult.Applied>(
            runtime.apply(first, plugin { replaceHandler(registry, LoginHandler(events, "first")) }),
        )
        assertIs<PatchApplyResult.Applied>(
            runtime.apply(second, plugin { replaceHandler(registry, LoginHandler(events, "second")) }),
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
        sequence: Long = 1,
    ): RuntimePatch {
        return RuntimePatch(
            id = PatchId(id),
            name = id,
            artifact = PatchArtifact("$id.jar", "sha256:$id"),
            compatibility = PatchCompatibility("game", setOf("1.0.0")),
            sequence = sequence,
        )
    }

    private fun plugin(block: PatchInstallContext.() -> Unit): RuntimePatchPlugin {
        return object : RuntimePatchPlugin {
            override suspend fun install(context: PatchInstallContext) {
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
    ) : MessageHandler<LoginReq> {
        override fun handle(context: HandlerContext, message: LoginReq) {
            events += "$name:${message.playerId}"
        }
    }
}
