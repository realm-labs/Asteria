package io.github.mikai233.asteria.patch

import io.github.mikai233.asteria.observability.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.Serializable

/**
 * Runtime patch unit loaded and applied by a node.
 *
 * Implementations should prefer declaring changes through [PatchInstallContext], especially
 * [PatchInstallContext.replace]. Replacement operations are tracked by the framework as ordered patch layers. When a
 * later patch is disabled, the registry is rebuilt from the remaining layers, so the active implementation falls back
 * to the previous patch instead of the original base entry.
 *
 * For type-keyed business services, use [PatchableServiceRegistry] and [PatchInstallContext.replace]. This gives
 * service patches the same ordered rollback behavior without requiring mutable static service variables.
 *
 * Keep patch instances small and deterministic. A patch may be applied during node startup replay or by a live GM
 * operation, so [install] should be safe to run once for the patch id on each target node and should fail before
 * creating irreversible side effects whenever possible.
 */
interface RuntimePatchPlugin {
    /**
     * Declares and initializes the patch.
     *
     * Use [PatchInstallContext.replace] for handler/service replacements. Those operations are committed only after
     * every operation validates successfully, and committed operations are rolled back if a later operation fails.
     *
     * Code that changes state outside [PatchInstallContext], such as registering listeners, starting background jobs,
     * or opening resources, is not automatically tracked. Such side effects should either happen after all replacement
     * declarations or be cleaned up by [uninstall].
     */
    suspend fun install(context: PatchInstallContext)

    /**
     * Cleans up side effects that the framework cannot track.
     *
     * Most pure replacement patches do not need to override this method. Registry replacements declared through
     * [PatchInstallContext.replace] are removed by the framework after [uninstall] returns. The registry then falls
     * back to the previous patch layer, or to the original base entry when no previous layer exists.
     *
     * Override this method for resources such as event subscriptions, scheduled tasks, metrics, temporary caches, file
     * handles, or background jobs created by [install]. Do not manually restore handler/service replacements here unless
     * they were changed outside [PatchInstallContext].
     */
    suspend fun uninstall(context: PatchUninstallContext) {
    }
}

/**
 * Records framework-managed operations for one patch installation.
 */
class PatchInstallContext internal constructor(
    private val order: PatchOrder,
) {
    private val operations: MutableList<PatchOperation> = mutableListOf()

    /**
     * Replaces one entry in [registry] for this patch layer.
     *
     * The replacement is ordered by the patch's [PatchOrder]. Disabling the patch removes only this layer and rebuilds
     * the registry from the remaining layers, preserving earlier patches.
     */
    fun <K : Any, V : Any> replace(registry: PatchSlotRegistry<K, V>, key: K, value: V) {
        operations.add(TargetReplacementOperation(registry, key, value, order))
    }

    internal fun operations(): List<PatchOperation> = operations.toList()
}

/**
 * Context passed to [RuntimePatchPlugin.uninstall].
 */
class PatchUninstallContext internal constructor(
    /**
     * Patch metadata for the patch being removed.
     */
    val patch: RuntimePatch,
)

class PatchRuntime(
    val environment: PatchEnvironment,
    private val tracer: Tracer = NoopTracer,
    private val metrics: Metrics = NoopMetrics,
) {
    private val logger = LoggerFactory.getLogger(PatchRuntime::class.java)
    private val lock = Mutex()
    private val applied: MutableMap<PatchId, AppliedRuntimePatch> = linkedMapOf()

    suspend fun apply(patch: RuntimePatch, plugin: RuntimePatchPlugin): PatchApplyResult {
        return tracer.span("patch.apply", patch.traceAttributes()) {
            metrics.counter("asteria.patch.apply.total", patch.metricTags()).increment()
            metrics.timer("asteria.patch.apply.duration", patch.metricTags()).record {
                lock.withLock {
                    val result = when {
                        patch.id in applied -> PatchApplyResult.Ignored(
                            patch.id,
                            "patch ${patch.id} is already applied"
                        )

                        patch.status != PatchStatus.Enabled -> PatchApplyResult.Ignored(
                            patch.id,
                            "patch ${patch.id} is ${patch.status}"
                        )

                        !patch.compatibility.matches(environment) -> PatchApplyResult.Ignored(
                            patch.id,
                            "patch ${patch.id} is not compatible with ${environment.appName}:${environment.version}",
                        )

                        !patch.target.matches(environment) -> PatchApplyResult.Ignored(
                            patch.id,
                            "patch ${patch.id} does not target this node",
                        )

                        else -> applyEnabled(patch, plugin)
                    }
                    val tags = patch.metricTags() + MetricTags.of("result" to result.resultTag())
                    metrics.counter("asteria.patch.apply.result.total", tags).increment()
                    event("patch.apply.result", TraceAttributes.of("patch.result" to result.resultTag()))
                    result
                }
            }
        }
    }

    suspend fun applyAll(patches: Iterable<Pair<RuntimePatch, RuntimePatchPlugin>>): List<PatchApplyResult> {
        val sorted = patches.sortedBy { it.first.order }
        return sorted.map { (patch, plugin) -> apply(patch, plugin) }
    }

    suspend fun remove(id: PatchId): Boolean {
        return tracer.span("patch.remove", TraceAttributes.of("patch.id" to id.value)) {
            metrics.counter("asteria.patch.remove.total", MetricTags.of("patch_id" to id.value)).increment()
            lock.withLock {
                val appliedPatch = applied.remove(id) ?: return@withLock false
                runCatching {
                    appliedPatch.plugin.uninstall(PatchUninstallContext(appliedPatch.patch))
                }.onFailure { error ->
                    metrics.counter("asteria.patch.uninstall.failed.total", appliedPatch.patch.metricTags()).increment()
                    this@span.error(error)
                    logger.error("patch {} uninstall failed", id.value, error)
                }
                appliedPatch.operations.asReversed().forEach { it.rollback() }
                metrics.counter("asteria.patch.remove.applied.total", appliedPatch.patch.metricTags()).increment()
                logger.info("patch removed id={}", id.value)
                true
            }
        }
    }

    fun appliedPatches(): List<RuntimePatch> {
        return applied.values.map { it.patch }.sortedBy { it.order }
    }

    private suspend fun applyEnabled(patch: RuntimePatch, plugin: RuntimePatchPlugin): PatchApplyResult {
        val context = PatchInstallContext(patch.order)
        try {
            plugin.install(context)
            val operations = context.operations()
            operations.forEach { it.validate() }
            val committed = mutableListOf<PatchOperation>()
            try {
                operations.forEach { operation ->
                    operation.commit()
                    committed.add(operation)
                }
                applied[patch.id] = AppliedRuntimePatch(patch, plugin, operations)
                logger.info(
                    "patch applied id={} operations={} priority={} sequence={}",
                    patch.id.value,
                    operations.size,
                    patch.priority,
                    patch.sequence,
                )
                return PatchApplyResult.Applied(patch.id, operations.size)
            } catch (error: Throwable) {
                committed.asReversed().forEach { it.rollback() }
                throw error
            }
        } catch (error: Throwable) {
            metrics.counter("asteria.patch.apply.failed.total", patch.metricTags()).increment()
            logger.error("patch {} apply failed", patch.id.value, error)
            throw error
        }
    }
}

private fun RuntimePatch.metricTags(): MetricTags {
    return MetricTags.of(
        "patch_id" to id.value,
        "app" to compatibility.appName,
    )
}

private fun RuntimePatch.traceAttributes(): TraceAttributes {
    return TraceAttributes.of(
        "patch.id" to id.value,
        "patch.name" to name,
        "patch.app" to compatibility.appName,
        "patch.priority" to priority.toString(),
        "patch.sequence" to sequence.toString(),
    )
}

private fun PatchApplyResult.resultTag(): String {
    return when (this) {
        is PatchApplyResult.Applied -> "applied"
        is PatchApplyResult.Ignored -> "ignored"
    }
}

sealed interface PatchApplyResult : Serializable {
    val patchId: PatchId

    data class Applied(
        override val patchId: PatchId,
        val operationCount: Int,
    ) : PatchApplyResult

    data class Ignored(
        override val patchId: PatchId,
        val reason: String,
    ) : PatchApplyResult
}

private data class AppliedRuntimePatch(
    val patch: RuntimePatch,
    val plugin: RuntimePatchPlugin,
    val operations: List<PatchOperation>,
)

internal interface PatchOperation {
    fun validate()

    fun commit()

    fun rollback()
}

private data class TargetReplacementOperation<K : Any, V : Any>(
    val registry: PatchSlotRegistry<K, V>,
    val key: K,
    val value: V,
    val order: PatchOrder,
) : PatchOperation {
    override fun validate() {
        check(registry.current(key) != null) { "patch registry key $key not found" }
    }

    override fun commit() {
        registry.replace(key, value, order)
    }

    override fun rollback() {
        registry.remove(order.id)
    }
}

/**
 * Replaces one service in [registry] using the reified service type.
 */
inline fun <reified T : Any> PatchInstallContext.replaceService(
    registry: PatchableServiceRegistry,
    service: T,
) {
    replace(registry, T::class, service)
}
