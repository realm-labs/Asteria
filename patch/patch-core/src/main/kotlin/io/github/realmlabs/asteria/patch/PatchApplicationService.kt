package io.github.realmlabs.asteria.patch

import io.github.realmlabs.asteria.observability.*
import org.slf4j.LoggerFactory

interface RuntimePatchPluginResolver {
    /**
     * Resolves the executable plugin implementation for [patch].
     */
    suspend fun resolve(patch: RuntimePatch): RuntimePatchPlugin

    /**
     * Optional cache eviction hook after a patch is disabled or otherwise no longer needed.
     */
    suspend fun evict(patch: RuntimePatch) {
    }
}

/**
 * In-memory resolver keyed by [PatchId].
 */
class StaticRuntimePatchPluginResolver(
    plugins: Map<PatchId, RuntimePatchPlugin> = emptyMap(),
) : RuntimePatchPluginResolver {
    private val plugins: MutableMap<PatchId, RuntimePatchPlugin> = plugins.toMutableMap()

    /**
     * Registers one plugin implementation for [id].
     */
    fun register(id: PatchId, plugin: RuntimePatchPlugin) {
        check(id !in plugins) { "patch plugin $id already registered" }
        plugins[id] = plugin
    }

    override suspend fun resolve(patch: RuntimePatch): RuntimePatchPlugin {
        return requireNotNull(plugins[patch.id]) { "patch plugin ${patch.id} not found" }
    }
}

/**
 * High-level application service for stored runtime patches.
 *
 * This service coordinates repository state, plugin resolution, and [PatchRuntime], but it is not itself a global
 * transaction manager. Repository updates and runtime apply/remove steps happen in a deliberate sequence chosen per
 * method.
 */
class PatchApplicationService(
    private val runtime: PatchRuntime,
    private val repository: RuntimePatchRepository,
    private val resolver: RuntimePatchPluginResolver,
    private val tracer: Tracer = NoopTracer,
    private val metrics: Metrics = NoopMetrics,
) {
    private val logger = LoggerFactory.getLogger(PatchApplicationService::class.java)

    /**
     * Environment of the underlying runtime patch engine.
     */
    val environment: PatchEnvironment get() = runtime.environment

    /**
     * Marks incompatible stored patches as expired and returns those patches.
     */
    suspend fun expireIncompatiblePatches(): List<RuntimePatch> {
        return tracer.span("patch.expire_incompatible", environment.traceAttributes()) {
            val expired = repository.expireIncompatible(runtime.environment)
            metrics.counter("asteria.patch.expired.total", environment.metricTags()).increment(expired.size.toLong())
            if (expired.isNotEmpty()) {
                logger.info(
                    "expired incompatible patches app={} version={} count={}",
                    environment.appName,
                    environment.version,
                    expired.size,
                )
            }
            expired
        }
    }

    /**
     * Loads every enabled compatible patch from the repository and applies them in patch order.
     *
     * The result is a per-patch report. One failing patch aborts the remaining tail of the batch because individual
     * [PatchRuntime.apply] failures throw.
     */
    suspend fun applyEnabledPatches(): PatchApplyReport {
        return tracer.span("patch.apply_enabled", environment.traceAttributes()) {
            val patches = repository.list(
                RuntimePatchQuery(
                    status = PatchStatus.Enabled,
                    appName = runtime.environment.appName,
                    version = runtime.environment.version,
                ),
            ).filter { it.canApplyTo(runtime.environment) }
            metrics.counter("asteria.patch.enabled.loaded.total", environment.metricTags())
                .increment(patches.size.toLong())
            applyPatches(patches)
        }
    }

    /**
     * Loads and applies one patch by id.
     */
    suspend fun apply(id: PatchId): PatchApplyResult {
        return tracer.span("patch.apply_one", TraceAttributes.of("patch.id" to id.value)) {
            val patch = requireNotNull(repository.find(id)) { "patch $id not found" }
            val plugin = resolver.resolve(patch)
            runtime.apply(patch, plugin)
        }
    }

    /**
     * Disables one patch in the repository and removes it from the live runtime if currently applied.
     *
     * The repository status is updated before runtime removal is attempted, so a `false` return value means either the
     * patch was absent or it was disabled but not currently applied in this runtime.
     */
    suspend fun disable(id: PatchId): Boolean {
        return tracer.span("patch.disable", TraceAttributes.of("patch.id" to id.value)) {
            val patch = repository.updateStatus(id, PatchStatus.Disabled) ?: return@span false
            val removed = runtime.remove(id)
            resolver.evict(patch)
            metrics.counter(
                "asteria.patch.disabled.total",
                environment.metricTags() + MetricTags.of("removed" to removed.toString()),
            ).increment()
            logger.info(
                "patch disabled id={} removed={}",
                id.value,
                removed,
            )
            removed
        }
    }

    private suspend fun applyPatches(patches: List<RuntimePatch>): PatchApplyReport {
        val results = patches
            .sortedBy { it.order }
            .map { patch -> runtime.apply(patch, resolver.resolve(patch)) }
        return PatchApplyReport(results)
    }
}

private fun PatchEnvironment.metricTags(): MetricTags {
    return MetricTags.of(
        "app" to appName,
        "version" to version,
    )
}

private fun PatchEnvironment.traceAttributes(): TraceAttributes {
    return TraceAttributes.of(
        "patch.app" to appName,
        "patch.version" to version,
        "patch.node" to (nodeAddress ?: ""),
    )
}

/**
 * Summary report for a batch patch application run.
 */
data class PatchApplyReport(
    val results: List<PatchApplyResult>,
) {
    /**
     * Number of results that represent a real successful application.
     */
    val appliedCount: Int get() = results.count { it is PatchApplyResult.Applied }
}
