package io.github.realmlabs.asteria.gm.patch

import io.github.realmlabs.asteria.patch.*
import java.time.Instant

interface GmPatchOperations {
    suspend fun create(
        request: GmPatchCreateRequest,
        artifactBytes: ByteArray,
    ): RuntimePatch

    suspend fun list(query: RuntimePatchQuery = RuntimePatchQuery()): List<RuntimePatch>

    suspend fun find(id: PatchId): RuntimePatch?

    suspend fun save(patch: RuntimePatch)

    suspend fun apply(id: PatchId): PatchClusterApplyResult

    suspend fun applyEnabled(): List<PatchClusterApplyResult>

    suspend fun nodeResults(query: RuntimePatchNodeResultQuery = RuntimePatchNodeResultQuery()): List<RuntimePatchNodeResult>

    suspend fun expireIncompatible(): List<RuntimePatch>

    suspend fun disable(id: PatchId): Boolean
}

class DefaultGmPatchOperations(
    private val repository: RuntimePatchRepository,
    private val applications: PatchApplicationService,
    private val artifacts: WritablePatchArtifactStore? = null,
    private val clusterApplications: PatchClusterApplicationService? = null,
    private val nodes: PatchNodeProvider? = null,
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

    override suspend fun apply(id: PatchId): PatchClusterApplyResult {
        repository.updateStatus(id, PatchStatus.Enabled) ?: error("patch $id not found")
        return clusterApplications?.apply(id) ?: applyLocal(id)
    }

    override suspend fun applyEnabled(): List<PatchClusterApplyResult> {
        clusterApplications?.let { return it.applyEnabledPatches() }
        val node = localNode()
        return applications.applyEnabledPatches().results.map {
            localResult(it, node, Instant.now())
        }
    }

    override suspend fun nodeResults(query: RuntimePatchNodeResultQuery): List<RuntimePatchNodeResult> {
        return clusterApplications?.results(query) ?: emptyList()
    }

    override suspend fun expireIncompatible(): List<RuntimePatch> {
        return applications.expireIncompatiblePatches()
    }

    override suspend fun disable(id: PatchId): Boolean {
        repository.updateStatus(id, PatchStatus.Disabled) ?: return false
        val clusterResult = clusterApplications?.disable(id)
        val localRemoved = applications.disable(id)
        return clusterResult?.succeeded ?: localRemoved
    }

    private suspend fun applyLocal(id: PatchId): PatchClusterApplyResult {
        val requestedAt = Instant.now()
        val result = applications.apply(id)
        return localResult(result, localNode(), requestedAt)
    }

    private suspend fun localNode(): PatchNode {
        return nodes?.nodes()?.singleOrNull() ?: PatchNode(
            nodeId = null,
            address = applications.environment.nodeAddress ?: "local",
            appName = applications.environment.appName,
            version = applications.environment.version,
            roles = applications.environment.roles,
        )
    }

    private fun localResult(
        result: PatchApplyResult,
        node: PatchNode,
        requestedAt: Instant,
    ): PatchClusterApplyResult {
        val nodeResult = when (result) {
            is PatchApplyResult.Applied -> RuntimePatchNodeResult(
                patchId = result.patchId,
                nodeId = node.nodeId,
                address = node.address,
                appName = node.appName,
                version = node.version,
                roles = node.roles,
                status = RuntimePatchNodeStatus.Applied,
                attempt = 1,
                operationCount = result.operationCount,
            )

            is PatchApplyResult.Ignored -> RuntimePatchNodeResult(
                patchId = result.patchId,
                nodeId = node.nodeId,
                address = node.address,
                appName = node.appName,
                version = node.version,
                roles = node.roles,
                status = RuntimePatchNodeStatus.Ignored,
                attempt = 1,
                message = result.reason,
            )
        }
        return PatchClusterApplyResult(result.patchId, requestedAt, listOf(nodeResult))
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
