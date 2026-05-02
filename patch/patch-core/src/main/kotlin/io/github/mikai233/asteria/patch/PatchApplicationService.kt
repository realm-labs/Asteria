package io.github.mikai233.asteria.patch

import io.github.mikai233.asteria.observability.MetricTags
import io.github.mikai233.asteria.observability.Metrics
import io.github.mikai233.asteria.observability.NoopMetrics
import io.github.mikai233.asteria.observability.NoopTracer
import io.github.mikai233.asteria.observability.TraceAttributes
import io.github.mikai233.asteria.observability.Tracer
import org.slf4j.LoggerFactory

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
    private val tracer: Tracer = NoopTracer,
    private val metrics: Metrics = NoopMetrics,
) {
    private val logger = LoggerFactory.getLogger(PatchApplicationService::class.java)

    val environment: PatchEnvironment get() = runtime.environment

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

    suspend fun applyEnabledPatches(): PatchApplyReport {
        return tracer.span("patch.apply_enabled", environment.traceAttributes()) {
            val patches = repository.list(
                RuntimePatchQuery(
                    status = PatchStatus.Enabled,
                    appName = runtime.environment.appName,
                    version = runtime.environment.version,
                ),
            ).filter { it.canApplyTo(runtime.environment) }
            metrics.counter("asteria.patch.enabled.loaded.total", environment.metricTags()).increment(patches.size.toLong())
            applyPatches(patches)
        }
    }

    suspend fun apply(id: PatchId): PatchApplyResult {
        return tracer.span("patch.apply_one", TraceAttributes.of("patch.id" to id.value)) {
            val patch = requireNotNull(repository.find(id)) { "patch $id not found" }
            val plugin = resolver.resolve(patch)
            runtime.apply(patch, plugin)
        }
    }

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

data class PatchApplyReport(
    val results: List<PatchApplyResult>,
) {
    val appliedCount: Int get() = results.count { it is PatchApplyResult.Applied }
}
