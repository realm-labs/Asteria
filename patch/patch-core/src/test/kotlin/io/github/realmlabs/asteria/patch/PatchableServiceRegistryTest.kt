package io.github.realmlabs.asteria.patch

import io.github.realmlabs.asteria.core.NodeRuntime
import io.github.realmlabs.asteria.core.NodeState
import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.core.ServiceRegistry
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PatchableServiceRegistryTest {
    @Test
    fun servicePatchFallsBackToPreviousPatchWhenLatestPatchIsRemoved() = runBlocking {
        val services = PatchableServiceRegistry()
        services.register(GreetingService::class, GreetingService("base"))
        val runtime = runtime()
        val first = patch("first", revision = 1)
        val second = patch("second", revision = 2)

        assertIs<PatchApplyResult.Applied>(
            runtime.apply(first, plugin { this.services.replace(services, GreetingService("first")) }),
        )
        assertIs<PatchApplyResult.Applied>(
            runtime.apply(second, plugin { this.services.replace(services, GreetingService("second")) }),
        )
        assertEquals("second", services.require<GreetingService>().value)

        assertEquals(true, runtime.remove(second.id))
        assertEquals("first", services.require<GreetingService>().value)

        assertEquals(true, runtime.remove(first.id))
        assertEquals("base", services.require<GreetingService>().value)
    }

    @Test
    fun serviceReplacementRequiresExistingBaseService() = runBlocking {
        val services = PatchableServiceRegistry()
        val runtime = runtime()

        val failed = runtime.apply(
            patch("missing", revision = 1),
            plugin { this.services.replace(services, GreetingService("patched")) },
        )

        assertIs<PatchApplyResult.Failed>(failed)
    }

    @Test
    fun serviceRegistryCanUseGenericPatchReplacementPath() = runBlocking {
        val services = PatchableServiceRegistry()
        services.register(GreetingService::class, GreetingService("base"))
        val runtime = runtime()

        val result = runtime.apply(
            patch("generic-service-replace", revision = 1),
            plugin {
                this.services.replace(services, GreetingService::class, GreetingService("patched"))
            },
        )

        assertIs<PatchApplyResult.Applied>(result)
        assertEquals("patched", services.require<GreetingService>().value)
    }

    @Test
    fun pluginCanRecordInstallPlanWithoutCommittingReplacement() = runBlocking {
        val services = PatchableServiceRegistry()
        services.register(GreetingService::class, GreetingService("base"))
        val plugin = plugin {
            this.services.replace(services, GreetingService("planned"))
        }

        val plan = plugin.recordInstallPlan(patch("recording", revision = 1), TestRuntime)

        assertEquals(listOf(GreetingService::class.toString()), plan.replacements.map { it.key })
        assertEquals("base", services.require<GreetingService>().value)
        plan.validate()
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

    private data class GreetingService(
        val value: String,
    )

    private object TestRuntime : NodeRuntime {
        override val name: String = "test"
        override val roles: Set<RoleKey> = emptySet()
        override val state: NodeState = NodeState.Started
        override val services: ServiceRegistry = ServiceRegistry()
    }
}
