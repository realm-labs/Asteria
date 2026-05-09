package io.github.realmlabs.asteria.patch

import io.github.realmlabs.asteria.core.gameApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class PatchApplicationServiceTest {
    @Test
    fun applicationServiceAppliesEnabledCompatiblePatchesInOrder() = runBlocking {
        val registry = PatchableRegistry(mapOf("handler" to "base"))
        val first = patch("first", priority = 0, sequence = 1)
        val second = patch("second", priority = 0, sequence = 2)
        val repository = InMemoryRuntimePatchRepository(listOf(second, first))
        val resolver = StaticRuntimePatchPluginResolver(
            mapOf(
                first.id to plugin { replace(registry, "handler", "first") },
                second.id to plugin { replace(registry, "handler", "second") },
            ),
        )
        val service = PatchApplicationService(
            runtime = runtime(),
            repository = repository,
            resolver = resolver,
        )

        val report = service.applyEnabledPatches()

        assertEquals(2, report.appliedCount)
        assertEquals("second", registry.require("handler"))
    }

    @Test
    fun applicationServiceSkipsDisabledAndIncompatiblePatchesBeforeResolvingPlugin() = runBlocking {
        val registry = PatchableRegistry(mapOf("handler" to "base"))
        val disabled = patch("disabled", sequence = 1, status = PatchStatus.Disabled)
        val incompatible = patch("incompatible", sequence = 2, versions = setOf("2.0.0"))
        val enabled = patch("enabled", sequence = 3)
        val repository = InMemoryRuntimePatchRepository(listOf(disabled, incompatible, enabled))
        val resolver = StaticRuntimePatchPluginResolver(
            mapOf(enabled.id to plugin { replace(registry, "handler", "enabled") }),
        )

        val report = PatchApplicationService(runtime(), repository, resolver).applyEnabledPatches()

        assertEquals(1, report.appliedCount)
        assertEquals("enabled", registry.require("handler"))
    }

    @Test
    fun moduleRegistersPatchServicesAndAppliesPatchesOnStart() = runBlocking {
        val registry = PatchableRegistry(mapOf("handler" to "base"))
        val patch = patch("startup", sequence = 1)
        val app = gameApplication {
            name = "patch-module-test"
            install(
                PatchModule {
                    environment = environment()
                    repository(InMemoryRuntimePatchRepository(listOf(patch)))
                    resolver(
                        StaticRuntimePatchPluginResolver(
                            mapOf(patch.id to plugin { replace(registry, "handler", "startup") }),
                        ),
                    )
                },
            )
        }

        try {
            app.launch()

            assertNotNull(app.services.find<PatchRuntime>())
            assertNotNull(app.services.find<RuntimePatchRepository>())
            assertNotNull(app.services.find<RuntimePatchPluginResolver>())
            assertNotNull(app.services.find<PatchApplicationService>())
            assertEquals("startup", registry.require("handler"))
        } finally {
            app.stop()
        }
    }

    @Test
    fun disablingPatchUpdatesRepositoryAndRemovesRuntimePatch() = runBlocking {
        val registry = PatchableRegistry(mapOf("handler" to "base"))
        val patch = patch("disable", sequence = 1)
        val repository = InMemoryRuntimePatchRepository(listOf(patch))
        val service = PatchApplicationService(
            runtime = runtime(),
            repository = repository,
            resolver = StaticRuntimePatchPluginResolver(
                mapOf(patch.id to plugin { replace(registry, "handler", "patched") }),
            ),
        )

        val applied = service.apply(patch.id)
        assertIs<PatchApplyResult.Applied>(applied)
        assertEquals("patched", registry.require("handler"))

        val disabled = service.disable(patch.id)

        assertEquals(true, disabled)
        assertEquals("base", registry.require("handler"))
        assertEquals(PatchStatus.Disabled, repository.find(patch.id)?.status)
    }

    @Test
    fun reconcileRemovesRuntimePatchThatIsNoLongerDesired() = runBlocking {
        val registry = PatchableRegistry(mapOf("handler" to "base"))
        val patch = patch("disable", sequence = 1)
        val repository = InMemoryRuntimePatchRepository(listOf(patch))
        val runtime = runtime()
        val service = PatchApplicationService(
            runtime = runtime,
            repository = repository,
            resolver = StaticRuntimePatchPluginResolver(
                mapOf(patch.id to plugin { replace(registry, "handler", "patched") }),
            ),
        )

        service.apply(patch.id)
        repository.updateStatus(patch.id, PatchStatus.Disabled)

        val report = service.reconcileEnabledPatches()

        assertEquals(0, report.appliedCount)
        assertEquals(listOf(patch.id), report.removedPatchIds)
        assertEquals("base", registry.require("handler"))
        assertEquals(emptyList(), runtime.appliedPatches())
    }

    @Test
    fun reconcileReappliesPatchWhenStoredMetadataChanges() = runBlocking {
        val registry = PatchableRegistry(mapOf("handler" to "base"))
        val patch = patch("reload", sequence = 1)
        val repository = InMemoryRuntimePatchRepository(listOf(patch))
        val runtime = runtime()
        val service = PatchApplicationService(
            runtime = runtime,
            repository = repository,
            resolver = object : RuntimePatchPluginResolver {
                override suspend fun resolve(patch: RuntimePatch): RuntimePatchPlugin {
                    return plugin { replace(registry, "handler", patch.artifact.checksum) }
                }
            },
        )

        service.reconcileEnabledPatches()
        assertEquals("sha256:reload", registry.require("handler"))

        val changed = patch.copy(artifact = PatchArtifact("reload.jar", "sha256:reload-v2"))
        repository.save(changed)
        val report = service.reconcileEnabledPatches()

        assertEquals(1, report.appliedCount)
        assertEquals(listOf(patch.id), report.removedPatchIds)
        assertEquals("sha256:reload-v2", registry.require("handler"))
        assertEquals(listOf(changed), runtime.appliedPatches())
    }

    @Test
    fun serviceExpiresEnabledPatchesThatDoNotMatchCurrentVersion() = runBlocking {
        val oldPatch = patch("old", sequence = 1, versions = setOf("0.9.0"))
        val currentPatch = patch("current", sequence = 2)
        val repository = InMemoryRuntimePatchRepository(listOf(oldPatch, currentPatch))
        val service = PatchApplicationService(
            runtime = runtime(),
            repository = repository,
            resolver = StaticRuntimePatchPluginResolver(),
        )

        val expired = service.expireIncompatiblePatches()

        assertEquals(listOf(oldPatch.id), expired.map { it.id })
        assertEquals(PatchStatus.Expired, repository.find(oldPatch.id)?.status)
        assertEquals(PatchStatus.Enabled, repository.find(currentPatch.id)?.status)
    }

    private fun runtime(): PatchRuntime {
        return PatchRuntime(environment())
    }

    private fun environment(): PatchEnvironment {
        return PatchEnvironment(
            appName = "game",
            version = "1.0.0",
            nodeAddress = "pekko://game@127.0.0.1:2551",
        )
    }

    private fun patch(
        id: String,
        priority: Int = 0,
        sequence: Long,
        versions: Set<String> = setOf("1.0.0"),
        status: PatchStatus = PatchStatus.Enabled,
    ): RuntimePatch {
        return RuntimePatch(
            id = PatchId(id),
            name = id,
            artifact = PatchArtifact("$id.jar", "sha256:$id"),
            compatibility = PatchCompatibility("game", versions),
            priority = priority,
            sequence = sequence,
            status = status,
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
