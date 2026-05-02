package io.github.mikai233.asteria.gm.patch

import io.github.mikai233.asteria.patch.PatchApplicationService
import io.github.mikai233.asteria.patch.PatchApplyResult
import io.github.mikai233.asteria.patch.PatchCompatibility
import io.github.mikai233.asteria.patch.PatchId
import io.github.mikai233.asteria.patch.PatchStatus
import io.github.mikai233.asteria.patch.PatchTarget
import io.github.mikai233.asteria.patch.RuntimePatch
import io.github.mikai233.asteria.patch.RuntimePatchQuery
import io.github.mikai233.asteria.patch.RuntimePatchRepository
import io.github.mikai233.asteria.patch.WritablePatchArtifactStore

interface GmPatchOperations {
    suspend fun create(
        request: GmPatchCreateRequest,
        artifactBytes: ByteArray,
    ): RuntimePatch

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
    private val artifacts: WritablePatchArtifactStore? = null,
) : GmPatchOperations {
    override suspend fun create(
        request: GmPatchCreateRequest,
        artifactBytes: ByteArray,
    ): RuntimePatch {
        val artifactStore = artifacts ?: error("writable patch artifact store is not configured")
        val artifact = artifactStore.save(
            name = request.artifactName,
            bytes = artifactBytes,
            version = request.artifactVersion,
        )
        val patch = RuntimePatch(
            id = request.id,
            name = request.name,
            artifact = artifact,
            compatibility = PatchCompatibility(request.appName, request.versions),
            target = request.target,
            priority = request.priority,
            sequence = repository.nextSequence(),
            status = request.status,
        )
        repository.save(patch)
        return patch
    }

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

data class GmPatchCreateRequest(
    val id: PatchId,
    val name: String,
    val artifactName: String,
    val artifactVersion: String? = null,
    val appName: String,
    val versions: Set<String>,
    val target: PatchTarget = PatchTarget.AllNodes,
    val priority: Int = 0,
    val status: PatchStatus = PatchStatus.Draft,
) {
    init {
        require(name.isNotBlank()) { "patch name must not be blank" }
        require(artifactName.isNotBlank()) { "patch artifact name must not be blank" }
        artifactVersion?.let { require(it.isNotBlank()) { "patch artifact version must not be blank" } }
        require(appName.isNotBlank()) { "patch app name must not be blank" }
        require(versions.isNotEmpty()) { "patch versions must not be empty" }
        versions.forEach { require(it.isNotBlank()) { "patch version must not be blank" } }
    }
}
