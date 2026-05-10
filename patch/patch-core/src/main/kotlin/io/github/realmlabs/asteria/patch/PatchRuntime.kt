package io.github.realmlabs.asteria.patch

import io.github.realmlabs.asteria.core.NodeRuntime
import io.github.realmlabs.asteria.observability.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.Serializable
import kotlin.reflect.KClass

/**
 * Runtime patch plugin loaded and applied by a node.
 *
 * Implementations declare runtime replacements through [RuntimePatchInstallContext]. Replacement operations are tracked
 * by the framework as ordered patch layers. When a newer patch is disabled, the registry is rebuilt from the remaining
 * layers, so the active implementation falls back to the previous patch instead of the original base entry.
 *
 * For type-keyed business services, use [RuntimePatchInstallContext.services]. This gives service patches the same
 * ordered rollback behavior without requiring mutable static service variables.
 *
 * Keep patch instances small and deterministic. A patch may be applied during node startup replay or by a live GM
 * operation, so [install] must be repeatable for the same patch id on each target node. Prefer pure replacement patches
 * that only declare registry changes.
 */
interface RuntimePatchPlugin {
    /**
     * Declares the runtime replacements owned by this patch.
     *
     * Use [RuntimePatchInstallContext.services] or module-specific replacement APIs for handler/service replacements.
     * Those operations are committed only after every operation validates successfully, and committed operations are
     * rolled back if a later operation fails.
     *
     * The normal patch model is replacement-only: [install] should declare service, message handler, event handler, or
     * custom registry replacements and avoid business state changes. If a specialized patch must create resources that
     * the framework cannot track, [install] may do so only when the resource is idempotent for this patch id and
     * [uninstall] releases it explicitly.
     */
    suspend fun install(context: RuntimePatchInstallContext)

    /**
     * Cleans up resources that [install] created outside framework-managed replacement registries.
     *
     * Most pure replacement patches do not need to override this method. Registry replacements declared through
     * [RuntimePatchInstallContext] are removed by the framework after [uninstall] returns. The registry then falls back
     * to the previous patch layer, or to the original base entry when no previous layer exists.
     *
     * Override this method only for specialized patches that create resources outside framework-managed replacement
     * registries, such as event subscriptions, scheduled tasks, temporary caches, file handles, or background jobs.
     * Do not manually restore handler/service replacements here unless they were changed outside
     * [RuntimePatchInstallContext].
     */
    suspend fun uninstall(context: PatchUninstallContext) {
    }
}

/**
 * Records framework-managed operations for one patch installation.
 */
class RuntimePatchInstallContext internal constructor(
    /**
     * Patch metadata for the patch being installed.
     */
    val patch: RuntimePatch,
    /**
     * Runtime view of the current node. Patch code can use [runtime.services] to locate application-provided
     * patch bindings and registries.
     */
    val runtime: NodeRuntime,
) {
    private val operations: MutableList<PatchOperation> = mutableListOf()

    /**
     * Type-keyed service replacements declared by this patch.
     */
    val services: RuntimePatchServiceReplacements = RuntimePatchServiceReplacements(this)

    /**
     * Returns the operations declared so far as an immutable snapshot.
     */
    internal fun operations(): List<PatchOperation> = operations.toList()

    internal fun plan(): RuntimePatchInstallPlan {
        return RuntimePatchInstallPlan(patch, operations())
    }

    /**
     * Records one framework-managed registry slot replacement.
     *
     * Business patch code should prefer [services] or module-specific APIs such as message/event handler replacement
     * helpers. Framework modules use this method to adapt their own patchable registries to the common runtime plan.
     */
    fun <K : Any, V : Any> replaceSlot(
        registry: PatchSlotRegistry<K, V>,
        key: K,
        value: V,
    ) {
        operations.add(TargetReplacementOperation(registry, key, value, patch.order))
    }

}

class RuntimePatchServiceReplacements internal constructor(
    private val context: RuntimePatchInstallContext,
) {
    /**
     * Replaces one service in an explicit [registry].
     */
    fun <T : Any> replace(
        registry: PatchableServiceRegistry,
        type: KClass<T>,
        service: T,
    ) {
        context.replaceSlot(registry, type, service)
    }
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
    val runtime: NodeRuntime,
    private val tracer: Tracer = NoopTracer,
    private val metrics: Metrics = NoopMetrics,
) {
    private val logger = LoggerFactory.getLogger(PatchRuntime::class.java)
    private val lock = Mutex()
    private val mutationScope = PatchRegistryMutationScope()
    private val applied: MutableMap<PatchId, AppliedRuntimePatch> = linkedMapOf()

    /**
     * Applies one patch execution if it is not already applied.
     *
     * Status, compatibility, and targeting are handled before a descriptor reaches this node-local runtime. Validation,
     * install, commit, or rollback failures throw.
     *
     * Successful application records the patch in the in-memory applied set so later [remove] calls can uninstall it.
     */
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

    /**
     * Applies several patches in ascending revision order.
     *
     * This method is sequential, not transactional across the whole batch: if one patch throws, previously applied
     * earlier patches remain applied.
     */
    suspend fun applyAll(patches: Iterable<Pair<RuntimePatch, RuntimePatchPlugin>>): List<PatchApplyResult> {
        val sorted = patches.sortedBy { it.first.order }
        return sorted.map { (patch, plugin) -> apply(patch, plugin) }
    }

    /**
     * Removes one previously applied patch.
     *
     * Returns `false` when the patch is not currently applied. Registry replacement layers are rolled back after
     * [RuntimePatchPlugin.uninstall] returns, even if uninstall itself throws.
     */
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
                appliedPatch.operations.asReversed().forEach { it.rollback(mutationScope) }
                metrics.counter("asteria.patch.remove.applied.total", appliedPatch.patch.metricTags()).increment()
                logger.info("patch removed id={}", id.value)
                true
            }
        }
    }

    /**
     * Returns the currently applied patches in effective order.
     */
    fun appliedPatches(): List<RuntimePatch> {
        return applied.values.map { it.patch }.sortedBy { it.order }
    }

    private suspend fun applyEnabled(patch: RuntimePatch, plugin: RuntimePatchPlugin): PatchApplyResult {
        val context = RuntimePatchInstallContext(patch, runtime)
        try {
            plugin.install(context)
            val plan = context.plan()
            val operations = plan.operations
            operations.forEach { it.validate() }
            val committed = mutableListOf<PatchOperation>()
            try {
                operations.forEach { operation ->
                    operation.commit(mutationScope)
                    committed.add(operation)
                }
                applied[patch.id] = AppliedRuntimePatch(patch, plugin, operations)
                logger.info(
                    "patch applied id={} operations={} revision={}",
                    patch.id.value,
                    operations.size,
                    patch.revision
                )
                return PatchApplyResult.Applied(patch.id, operations.size)
            } catch (error: Throwable) {
                committed.asReversed().forEach { it.rollback(mutationScope) }
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
        "revision" to revision.toString(),
    )
}

private fun RuntimePatch.traceAttributes(): TraceAttributes {
    return TraceAttributes.of(
        "patch.id" to id.value,
        "patch.revision" to revision.toString(),
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

    /**
     * Patch was applied and committed successfully.
     */
    data class Applied(
        override val patchId: PatchId,
        val operationCount: Int,
    ) : PatchApplyResult

    /**
     * Patch was skipped without changing runtime state.
     */
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
    val target: RuntimePatchReplacementTarget

    fun validate()

    fun commit(scope: PatchRegistryMutationScope)

    fun rollback(scope: PatchRegistryMutationScope)
}

private data class TargetReplacementOperation<K : Any, V : Any>(
    val registry: PatchSlotRegistry<K, V>,
    val key: K,
    val value: V,
    val order: PatchOrder,
) : PatchOperation {
    override val target: RuntimePatchReplacementTarget = RuntimePatchReplacementTarget(
        registryType = registry::class.qualifiedName ?: registry::class.toString(),
        key = key.toString(),
    )

    /**
     * Ensures the target key already exists in the registry's active view.
     */
    override fun validate() {
        check(registry.current(key) != null) { "patch registry key $key not found" }
    }

    /**
     * Commits this patch layer replacement.
     */
    override fun commit(scope: PatchRegistryMutationScope) {
        registry.replace(key, value, order, scope)
    }

    /**
     * Removes the whole replacement layer owned by this operation's patch id.
     */
    override fun rollback(scope: PatchRegistryMutationScope) {
        registry.remove(order.id, scope)
    }
}

/**
 * Replaces one service in an explicit [registry].
 */
inline fun <reified T : Any> RuntimePatchServiceReplacements.replace(
    registry: PatchableServiceRegistry,
    service: T,
) {
    replace(registry, T::class, service)
}

class RuntimePatchInstallPlan internal constructor(
    val patch: RuntimePatch,
    internal val operations: List<PatchOperation>,
) {
    val replacements: List<RuntimePatchReplacementTarget> = operations.map { it.target }

    fun validate() {
        operations.forEach { it.validate() }
    }
}

data class RuntimePatchReplacementTarget(
    val registryType: String,
    val key: String,
)

suspend fun RuntimePatchPlugin.recordInstallPlan(
    patch: RuntimePatch,
    runtime: NodeRuntime,
): RuntimePatchInstallPlan {
    val context = RuntimePatchInstallContext(patch, runtime)
    install(context)
    return context.plan()
}
