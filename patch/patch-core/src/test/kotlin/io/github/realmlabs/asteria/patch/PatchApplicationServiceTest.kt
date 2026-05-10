package io.github.realmlabs.asteria.patch

import io.github.realmlabs.asteria.core.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class PatchApplicationServiceTest {
    @Test
    fun applicationServiceAppliesEnabledCompatiblePatchesInOrder() = runBlocking {
        val registry = PatchableRegistry(mapOf("handler" to "base"))
        val first = patch("first", revision = 1)
        val second = patch("second", revision = 2)
        val repository = InMemoryRuntimePatchRepository(listOf(second, first))
        val resolver = StaticRuntimePatchPluginResolver(
            mapOf(
                first.id to plugin { replaceSlot(registry, "handler", "first") },
                second.id to plugin { replaceSlot(registry, "handler", "second") },
            ),
        )
        val service = PatchApplicationService(
            runtime = runtime(),
            environment = environment(),
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
        val disabled = patch("disabled", revision = 1, status = PatchStatus.Disabled)
        val incompatible = patch("incompatible", revision = 2, versions = setOf("2.0.0"))
        val enabled = patch("enabled", revision = 3)
        val repository = InMemoryRuntimePatchRepository(listOf(disabled, incompatible, enabled))
        val resolver = StaticRuntimePatchPluginResolver(
            mapOf(enabled.id to plugin { replaceSlot(registry, "handler", "enabled") }),
        )

        val report = PatchApplicationService(runtime(), environment(), repository, resolver).applyEnabledPatches()

        assertEquals(1, report.appliedCount)
        assertEquals("enabled", registry.require("handler"))
    }

    @Test
    fun applicationServiceContinuesAfterFailedPatch() = runBlocking {
        val registry = PatchableRegistry(mapOf("handler" to "base"))
        val broken = patch("broken", revision = 1)
        val valid = patch("valid", revision = 2)
        val repository = InMemoryRuntimePatchRepository(listOf(broken, valid))
        val resolver = StaticRuntimePatchPluginResolver(
            mapOf(valid.id to plugin { replaceSlot(registry, "handler", "valid") }),
        )

        val report = PatchApplicationService(runtime(), environment(), repository, resolver).applyEnabledPatches()

        assertIs<PatchApplyResult.Failed>(report.results.first())
        assertEquals(1, report.appliedCount)
        assertEquals("valid", registry.require("handler"))
    }

    @Test
    fun moduleRegistersPatchServicesAndAppliesPatchesOnStart() = runBlocking {
        val registry = PatchableRegistry(mapOf("handler" to "base"))
        val patch = patch("startup", revision = 1)
        val app = gameApplication {
            name = "patch-module-test"
            install(
                PatchModule {
                    environment = environment()
                    repository(InMemoryRuntimePatchRepository(listOf(patch)))
                    resolver(
                        StaticRuntimePatchPluginResolver(
                            mapOf(patch.id to plugin { replaceSlot(registry, "handler", "startup") }),
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
        val patch = patch("disable", revision = 1)
        val repository = InMemoryRuntimePatchRepository(listOf(patch))
        val service = PatchApplicationService(
            runtime = runtime(),
            environment = environment(),
            repository = repository,
            resolver = StaticRuntimePatchPluginResolver(
                mapOf(patch.id to plugin { replaceSlot(registry, "handler", "patched") }),
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
        val patch = patch("disable", revision = 1)
        val repository = InMemoryRuntimePatchRepository(listOf(patch))
        val runtime = runtime()
        val service = PatchApplicationService(
            runtime = runtime,
            environment = environment(),
            repository = repository,
            resolver = StaticRuntimePatchPluginResolver(
                mapOf(patch.id to plugin { replaceSlot(registry, "handler", "patched") }),
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
        val patch = patch("reload", revision = 1)
        val repository = InMemoryRuntimePatchRepository(listOf(patch))
        val runtime = runtime()
        val service = PatchApplicationService(
            runtime = runtime,
            environment = environment(),
            repository = repository,
            resolver = object : RuntimePatchPluginResolver {
                override suspend fun resolve(patch: RuntimePatchDescriptor): RuntimePatchPlugin {
                    return plugin { replaceSlot(registry, "handler", patch.artifact.checksum) }
                }
            },
        )

        service.reconcileEnabledPatches()
        assertEquals("sha256:reload", registry.require("handler"))

        val changed = repository.save(patch.copy(artifact = PatchArtifact("reload.jar", "sha256:reload-v2")))
        val report = service.reconcileEnabledPatches()

        assertEquals(1, report.appliedCount)
        assertEquals(listOf(patch.id), report.removedPatchIds)
        assertEquals("sha256:reload-v2", registry.require("handler"))
        assertEquals(listOf(changed.execution()), runtime.appliedPatches())
    }

    @Test
    fun serviceExpiresEnabledPatchesThatDoNotMatchCurrentVersion() = runBlocking {
        val oldPatch = patch("old", revision = 1, versions = setOf("0.9.0"))
        val currentPatch = patch("current", revision = 2)
        val repository = InMemoryRuntimePatchRepository(listOf(oldPatch, currentPatch))
        val service = PatchApplicationService(
            runtime = runtime(),
            environment = environment(),
            repository = repository,
            resolver = StaticRuntimePatchPluginResolver(),
        )

        val expired = service.expireIncompatiblePatches()

        assertEquals(listOf(oldPatch.id), expired.map { it.id })
        assertEquals(PatchStatus.Expired, repository.find(oldPatch.id)?.status)
        assertEquals(PatchStatus.Enabled, repository.find(currentPatch.id)?.status)
    }

    private fun runtime(): PatchRuntime {
        return PatchRuntime(TestRuntime)
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
        revision: Long,
        versions: Set<String> = setOf("1.0.0"),
        status: PatchStatus = PatchStatus.Enabled,
    ): RuntimePatchDescriptor {
        return RuntimePatchDescriptor(
            id = PatchId(id),
            artifact = PatchArtifact("$id.jar", "sha256:$id"),
            compatibility = PatchCompatibility("game", versions),
            name = id,
            status = status,
            revision = revision,
        )
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
