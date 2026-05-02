package io.github.mikai233.asteria.patch

interface RuntimePatchPluginResolver {
    suspend fun resolve(patch: RuntimePatch): RuntimePatchPlugin

    suspend fun evict(patch: RuntimePatch) {
    }
}

class StaticRuntimePatchPluginResolver(
    plugins: Map<PatchId, RuntimePatchPlugin> = emptyMap(),
) : RuntimePatchPluginResolver {
    private val plugins: MutableMap<PatchId, RuntimePatchPlugin> = plugins.toMutableMap()

    fun register(id: PatchId, plugin: RuntimePatchPlugin) {
        check(id !in plugins) { "patch plugin $id already registered" }
        plugins[id] = plugin
    }

    override suspend fun resolve(patch: RuntimePatch): RuntimePatchPlugin {
        return requireNotNull(plugins[patch.id]) { "patch plugin ${patch.id} not found" }
    }
}

class PatchApplicationService(
    private val runtime: PatchRuntime,
    private val repository: RuntimePatchRepository,
    private val resolver: RuntimePatchPluginResolver,
) {
    val environment: PatchEnvironment get() = runtime.environment

    suspend fun expireIncompatiblePatches(): List<RuntimePatch> {
        return repository.expireIncompatible(runtime.environment)
    }

    suspend fun applyEnabledPatches(): PatchApplyReport {
        val patches = repository.list(
            RuntimePatchQuery(
                status = PatchStatus.Enabled,
                appName = runtime.environment.appName,
                version = runtime.environment.version,
            ),
        ).filter { it.canApplyTo(runtime.environment) }

        return applyPatches(patches)
    }

    suspend fun apply(id: PatchId): PatchApplyResult {
        val patch = requireNotNull(repository.find(id)) { "patch $id not found" }
        val plugin = resolver.resolve(patch)
        return runtime.apply(patch, plugin)
    }

    suspend fun disable(id: PatchId): Boolean {
        val patch = repository.updateStatus(id, PatchStatus.Disabled) ?: return false
        val removed = runtime.remove(id)
        resolver.evict(patch)
        return removed
    }

    private suspend fun applyPatches(patches: List<RuntimePatch>): PatchApplyReport {
        val results = patches
            .sortedBy { it.order }
            .map { patch -> runtime.apply(patch, resolver.resolve(patch)) }
        return PatchApplyReport(results)
    }
}

data class PatchApplyReport(
    val results: List<PatchApplyResult>,
) {
    val appliedCount: Int get() = results.count { it is PatchApplyResult.Applied }
}
