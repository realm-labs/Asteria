package io.github.realmlabs.asteria.patch

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
                replace(registry, "login", "new")
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

        runtime.apply(first, plugin { replace(registry, "handler", "first") })
        runtime.apply(second, plugin { replace(registry, "handler", "second") })

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

        runtime.apply(normal, plugin { replace(registry, "handler", "normal") })
        runtime.apply(urgent, plugin { replace(registry, "handler", "urgent") })

        assertEquals("urgent", registry.require("handler"))

        assertTrue(runtime.remove(urgent.id))
        assertEquals("normal", registry.require("handler"))
    }

    @Test
    fun failedPatchDoesNotCommitPartialRegistryChanges() = runBlocking {
        val registry = PatchableRegistry(mapOf("handler" to "base"))
        val runtime = runtime()
        val patch = patch("broken", revision = 1)

        val failed = runCatching {
            runtime.apply(
                patch,
                plugin {
                    replace(registry, "handler", "patched")
                    replace(registry, "missing", "bad")
                },
            )
        }

        assertTrue(failed.isFailure)
        assertEquals("base", registry.require("handler"))
        assertEquals(emptyList(), runtime.appliedPatches())
    }

    @Test
    fun disabledPatchCanBeRemovedOnlyWhenApplied() = runBlocking {
        val runtime = runtime()
        assertFalse(runtime.remove(PatchId("missing")))
    }

    private fun runtime(): PatchRuntime {
        return PatchRuntime()
    }

    private fun patch(
        id: String,
        revision: Long,
    ): RuntimePatch {
        return RuntimePatch(PatchId(id), revision)
    }

    private fun plugin(block: PatchInstallContext.() -> Unit): RuntimePatchPlugin {
        return object : RuntimePatchPlugin {
            override suspend fun install(context: PatchInstallContext) {
                context.block()
            }
        }
    }
}
