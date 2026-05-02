package io.github.mikai233.asteria.patch

import java.io.Serializable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface RuntimePatchPlugin {
    suspend fun install(context: PatchInstallContext)

    suspend fun uninstall(context: PatchUninstallContext) {
    }
}

class PatchInstallContext internal constructor(
    private val order: PatchOrder,
) {
    private val operations: MutableList<PatchOperation> = mutableListOf()

    fun <K : Any, V : Any> replace(registry: PatchableRegistry<K, V>, key: K, value: V) {
        operations.add(RegistryReplacementOperation(registry, key, value, order))
    }

    internal fun operations(): List<PatchOperation> = operations.toList()
}

class PatchUninstallContext internal constructor(
    val patch: RuntimePatch,
)

class PatchRuntime(
    val environment: PatchEnvironment,
) {
    private val lock = Mutex()
    private val applied: MutableMap<PatchId, AppliedRuntimePatch> = linkedMapOf()

    suspend fun apply(patch: RuntimePatch, plugin: RuntimePatchPlugin): PatchApplyResult {
        return lock.withLock {
            when {
                patch.id in applied -> PatchApplyResult.Ignored(patch.id, "patch ${patch.id} is already applied")
                patch.status != PatchStatus.Enabled -> PatchApplyResult.Ignored(patch.id, "patch ${patch.id} is ${patch.status}")
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
        }
    }

    suspend fun applyAll(patches: Iterable<Pair<RuntimePatch, RuntimePatchPlugin>>): List<PatchApplyResult> {
        val sorted = patches.sortedBy { it.first.order }
        return sorted.map { (patch, plugin) -> apply(patch, plugin) }
    }

    suspend fun remove(id: PatchId): Boolean {
        return lock.withLock {
            val appliedPatch = applied.remove(id) ?: return@withLock false
            runCatching {
                appliedPatch.plugin.uninstall(PatchUninstallContext(appliedPatch.patch))
            }
            appliedPatch.operations.asReversed().forEach { it.rollback() }
            true
        }
    }

    fun appliedPatches(): List<RuntimePatch> {
        return applied.values.map { it.patch }.sortedBy { it.order }
    }

    private suspend fun applyEnabled(patch: RuntimePatch, plugin: RuntimePatchPlugin): PatchApplyResult {
        val context = PatchInstallContext(patch.order)
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
            return PatchApplyResult.Applied(patch.id, operations.size)
        } catch (error: Throwable) {
            committed.asReversed().forEach { it.rollback() }
            throw error
        }
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

private data class RegistryReplacementOperation<K : Any, V : Any>(
    val registry: PatchableRegistry<K, V>,
    val key: K,
    val value: V,
    val order: PatchOrder,
) : PatchOperation {
    override fun validate() {
        registry.require(key)
    }

    override fun commit() {
        registry.replace(key, value, order)
    }

    override fun rollback() {
        registry.removePatch(order.id)
    }
}
