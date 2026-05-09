package io.github.realmlabs.asteria.patch

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            runtime.apply(first, plugin { replaceService<GreetingService>(services, GreetingService("first")) }),
        )
        assertIs<PatchApplyResult.Applied>(
            runtime.apply(second, plugin { replaceService<GreetingService>(services, GreetingService("second")) }),
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

        val failed = runCatching {
            runtime.apply(
                patch("missing", revision = 1),
                plugin { replaceService<GreetingService>(services, GreetingService("patched")) },
            )
        }

        assertFalse(failed.isSuccess)
    }

    @Test
    fun serviceRegistryCanUseGenericPatchReplacementPath() = runBlocking {
        val services = PatchableServiceRegistry()
        services.register(GreetingService::class, GreetingService("base"))
        val runtime = runtime()

        val result = runtime.apply(
            patch("generic-service-replace", revision = 1),
            plugin {
                replace(services, GreetingService::class, GreetingService("patched"))
            },
        )

        assertIs<PatchApplyResult.Applied>(result)
        assertEquals("patched", services.require<GreetingService>().value)
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

    private data class GreetingService(
        val value: String,
    )
}
