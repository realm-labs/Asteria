package io.github.realmlabs.asteria.patch

import io.github.realmlabs.asteria.core.RoleKey
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class PatchRuntimeTest {
    @Test
    fun registryReplacementUsesCopyOnWriteSnapshot() = runBlocking {
        val registry = PatchableRegistry(mapOf("login" to "old"))
        val snapshot = registry.snapshot()
        val runtime = runtime()
        val patch = patch("fix-login", sequence = 1)

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
        val first = patch("first", priority = 0, sequence = 1)
        val second = patch("second", priority = 0, sequence = 2)

        runtime.apply(first, plugin { replace(registry, "handler", "first") })
        runtime.apply(second, plugin { replace(registry, "handler", "second") })

        assertEquals("second", registry.require("handler"))

        assertTrue(runtime.remove(second.id))
        assertEquals("first", registry.require("handler"))

        assertTrue(runtime.remove(first.id))
        assertEquals("base", registry.require("handler"))
    }

    @Test
    fun patchOrderIsStableWhenLowerPriorityPatchIsAppliedLater() = runBlocking {
        val registry = PatchableRegistry(mapOf("handler" to "base"))
        val runtime = runtime()
        val normal = patch("normal", priority = 10, sequence = 1)
        val urgent = patch("urgent", priority = 0, sequence = 2)

        runtime.apply(normal, plugin { replace(registry, "handler", "normal") })
        runtime.apply(urgent, plugin { replace(registry, "handler", "urgent") })

        assertEquals("normal", registry.require("handler"))

        assertTrue(runtime.remove(normal.id))
        assertEquals("urgent", registry.require("handler"))
    }

    @Test
    fun incompatiblePatchIsIgnored() = runBlocking {
        val registry = PatchableRegistry(mapOf("handler" to "base"))
        val runtime = runtime(version = "2.0.0")
        val patch = patch("old-version", sequence = 1, versions = setOf("1.0.0"))

        val result = runtime.apply(patch, plugin { replace(registry, "handler", "patched") })

        assertIs<PatchApplyResult.Ignored>(result)
        assertEquals("base", registry.require("handler"))
        assertEquals(emptyList(), runtime.appliedPatches())
    }

    @Test
    fun targetRoleMustMatchEnvironment() = runBlocking {
        val registry = PatchableRegistry(mapOf("handler" to "base"))
        val runtime = runtime(roles = setOf(RoleKey("player")))
        val patch = patch(
            id = "world-only",
            sequence = 1,
            target = PatchTarget.Roles(setOf(RoleKey("world"))),
        )

        val result = runtime.apply(patch, plugin { replace(registry, "handler", "patched") })

        assertIs<PatchApplyResult.Ignored>(result)
        assertEquals("base", registry.require("handler"))
    }

    @Test
    fun failedPatchDoesNotCommitPartialRegistryChanges() = runBlocking {
        val registry = PatchableRegistry(mapOf("handler" to "base"))
        val runtime = runtime()
        val patch = patch("broken", sequence = 1)

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

    private fun runtime(
        version: String = "1.0.0",
        roles: Set<RoleKey> = setOf(RoleKey("player")),
    ): PatchRuntime {
        return PatchRuntime(
            PatchEnvironment(
                appName = "game",
                version = version,
                nodeAddress = "pekko://game@127.0.0.1:2551",
                roles = roles,
            ),
        )
    }

    private fun patch(
        id: String,
        priority: Int = 0,
        sequence: Long,
        versions: Set<String> = setOf("1.0.0"),
        target: PatchTarget = PatchTarget.AllNodes,
    ): RuntimePatch {
        return RuntimePatch(
            id = PatchId(id),
            name = id,
            artifact = PatchArtifact("$id.jar", "sha256:$id"),
            compatibility = PatchCompatibility("game", versions),
            target = target,
            priority = priority,
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
}
