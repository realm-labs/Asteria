package io.github.realmlabs.asteria.patch

import io.github.realmlabs.asteria.core.NodeRuntime
import io.github.realmlabs.asteria.core.NodeState
import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.core.ServiceRegistry
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class PatchRuntimeTest {
    @Test
    fun registryReplacementUsesCopyOnWriteSnapshot() = runBlocking {
        val registry = PatchableRegistry(mapOf("login" to "old"))
        val snapshot = registry.snapshot()
        val runtime = runtime()
        val patch = patch("fix-login", revision = 1)

        val result = runtime.apply(
            patch,
            plugin {
                replaceSlot(registry, "login", "new")
            },
        )

        assertIs<PatchApplyResult.Applied>(result)
        assertEquals("old", snapshot["login"])
        assertEquals("new", registry.require("login"))
    }

    @Test
    fun laterPatchOverridesEarlierPatchAndRemoveRestoresPreviousLayer() = runBlocking {
        val registry = PatchableRegistry(mapOf("handler" to "base"))
        val runtime = runtime()
        val first = patch("first", revision = 1)
        val second = patch("second", revision = 2)

        runtime.apply(first, plugin { replaceSlot(registry, "handler", "first") })
        runtime.apply(second, plugin { replaceSlot(registry, "handler", "second") })

        assertEquals("second", registry.require("handler"))

        assertTrue(runtime.remove(second.id))
        assertEquals("first", registry.require("handler"))

        assertTrue(runtime.remove(first.id))
        assertEquals("base", registry.require("handler"))
    }

    @Test
    fun newerPatchRevisionWinsEvenWhenAppliedLater() = runBlocking {
        val registry = PatchableRegistry(mapOf("handler" to "base"))
        val runtime = runtime()
        val normal = patch("normal", revision = 1)
        val urgent = patch("urgent", revision = 2)

        runtime.apply(normal, plugin { replaceSlot(registry, "handler", "normal") })
        runtime.apply(urgent, plugin { replaceSlot(registry, "handler", "urgent") })

        assertEquals("urgent", registry.require("handler"))

        assertTrue(runtime.remove(urgent.id))
        assertEquals("normal", registry.require("handler"))
    }

    @Test
    fun failedPatchDoesNotCommitPartialRegistryChanges() = runBlocking {
        val registry = PatchableRegistry(mapOf("handler" to "base"))
        val runtime = runtime()
        val patch = patch("broken", revision = 1)

        val failed = runtime.apply(
            patch,
            plugin {
                replaceSlot(registry, "handler", "patched")
                replaceSlot(registry, "missing", "bad")
            },
        )

        assertIs<PatchApplyResult.Failed>(failed)
        assertEquals("base", registry.require("handler"))
        assertEquals(emptyList(), runtime.appliedPatches())
    }

    @Test
    fun disabledPatchCanBeRemovedOnlyWhenApplied() = runBlocking {
        val runtime = runtime()
        assertFalse(runtime.remove(PatchId("missing")))
    }

    private fun runtime(): PatchRuntime {
        return PatchRuntime(TestRuntime)
    }

    private fun patch(
        id: String,
        revision: Long,
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
}
