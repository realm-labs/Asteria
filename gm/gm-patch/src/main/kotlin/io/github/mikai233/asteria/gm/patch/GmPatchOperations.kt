package io.github.mikai233.asteria.gm.patch

import io.github.mikai233.asteria.patch.PatchApplicationService
import io.github.mikai233.asteria.patch.PatchApplyResult
import io.github.mikai233.asteria.patch.PatchId
import io.github.mikai233.asteria.patch.PatchStatus
import io.github.mikai233.asteria.patch.RuntimePatch
import io.github.mikai233.asteria.patch.RuntimePatchQuery
import io.github.mikai233.asteria.patch.RuntimePatchRepository

interface GmPatchOperations {
    suspend fun list(query: RuntimePatchQuery = RuntimePatchQuery()): List<RuntimePatch>

    suspend fun find(id: PatchId): RuntimePatch?

    suspend fun save(patch: RuntimePatch)

    suspend fun apply(id: PatchId): PatchApplyResult

    suspend fun applyEnabled(): List<PatchApplyResult>

    suspend fun disable(id: PatchId): Boolean
}

class DefaultGmPatchOperations(
    private val repository: RuntimePatchRepository,
    private val applications: PatchApplicationService,
) : GmPatchOperations {
    override suspend fun list(query: RuntimePatchQuery): List<RuntimePatch> {
        return repository.list(query)
    }

    override suspend fun find(id: PatchId): RuntimePatch? {
        return repository.find(id)
    }

    override suspend fun save(patch: RuntimePatch) {
        repository.save(patch)
    }

    override suspend fun apply(id: PatchId): PatchApplyResult {
        repository.updateStatus(id, PatchStatus.Enabled) ?: error("patch $id not found")
        return applications.apply(id)
    }

    override suspend fun applyEnabled(): List<PatchApplyResult> {
        return applications.applyEnabledPatches().results
    }

    override suspend fun disable(id: PatchId): Boolean {
        return applications.disable(id)
    }
}
