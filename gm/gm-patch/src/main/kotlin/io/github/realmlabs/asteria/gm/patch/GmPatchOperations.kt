package io.github.realmlabs.asteria.gm.patch

import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.patch.*
import java.time.Instant
import java.util.jar.JarInputStream

/**
 * GM-facing facade for runtime patch management.
 *
 * Implementations are responsible for preserving the GM authorization/audit boundary at the caller. Methods here
 * perform patch storage, desired-state changes, cluster fan-out, and result inspection.
 */
interface GmPatchOperations {
    suspend fun create(
        request: GmPatchCreateRequest,
        artifactBytes: ByteArray,
    ): RuntimePatchDescriptor

    suspend fun list(query: RuntimePatchQuery = RuntimePatchQuery()): List<RuntimePatchDescriptor>

    suspend fun find(id: PatchId): RuntimePatchDescriptor?

    suspend fun save(patch: RuntimePatchDescriptor): RuntimePatchDescriptor

    suspend fun apply(id: PatchId): PatchClusterApplyResult

    suspend fun applyEnabled(): List<PatchClusterApplyResult>

    suspend fun nodeResults(query: RuntimePatchNodeResultQuery = RuntimePatchNodeResultQuery()): List<RuntimePatchNodeResult>

    suspend fun expireIncompatible(): List<RuntimePatchDescriptor>

    suspend fun disable(id: PatchId): Boolean
}

/**
 * Default patch facade backed by the runtime patch repository and application services.
 *
 * Creation stores artifact bytes when a writable artifact store is configured, infers requirements from jar manifest
 * attributes when the request does not provide them, and validates that requirement constraints match selected nodes.
 */
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
    ): RuntimePatchDescriptor {
        val artifactStore = artifacts ?: error("writable patch artifact store is not configured")
        val artifact = artifactStore.save(
            name = request.artifactName,
            bytes = artifactBytes,
            version = request.artifactVersion,
        )
        val requirements = request.requirements.takeUnless { it.isEmpty } ?: artifactBytes.patchRequirements()
        val patch = RuntimePatchDescriptor(
            id = request.id,
            name = request.name,
            artifact = artifact,
            compatibility = PatchCompatibility(request.appName, request.versions),
            requirements = requirements,
            target = request.target,
            status = request.status,
        )
        validateTarget(patch)
        return repository.save(patch)
    }

    override suspend fun list(query: RuntimePatchQuery): List<RuntimePatchDescriptor> {
        return repository.list(query)
    }

    override suspend fun find(id: PatchId): RuntimePatchDescriptor? {
        return repository.find(id)
    }

    override suspend fun save(patch: RuntimePatchDescriptor): RuntimePatchDescriptor {
        return repository.save(patch)
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

    override suspend fun expireIncompatible(): List<RuntimePatchDescriptor> {
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
                modules = node.modules,
                capabilities = node.capabilities,
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
                modules = node.modules,
                capabilities = node.capabilities,
                status = RuntimePatchNodeStatus.Ignored,
                attempt = 1,
                message = result.reason,
            )

            is PatchApplyResult.Failed -> RuntimePatchNodeResult(
                patchId = result.patchId,
                nodeId = node.nodeId,
                address = node.address,
                appName = node.appName,
                version = node.version,
                roles = node.roles,
                modules = node.modules,
                capabilities = node.capabilities,
                status = RuntimePatchNodeStatus.Failed,
                attempt = 1,
                message = result.message,
            )
        }
        return PatchClusterApplyResult(result.patchId, requestedAt, listOf(nodeResult))
    }

    private suspend fun validateTarget(patch: RuntimePatchDescriptor) {
        val provider = nodes ?: return
        if (patch.requirements.isEmpty) {
            return
        }
        val selected = provider.nodes()
            .filter { node -> patch.compatibility.matches(node.environment()) && patch.target.matches(node.environment()) }
        require(selected.isNotEmpty()) {
            "patch ${patch.id} target does not match any known node"
        }
        val invalid = selected.filterNot { node -> patch.requirements.matches(node.environment()) }
        require(invalid.isEmpty()) {
            "patch ${patch.id} requirements do not match target nodes: ${invalid.joinToString { it.address }}"
        }
    }
}

/**
 * Request used by GM to create or register a runtime patch.
 *
 * [target] selects the nodes that should receive the patch. [requirements] adds capabilities that selected nodes must
 * satisfy before creation is accepted; when empty, jar manifest attributes may supply requirements.
 */
data class GmPatchCreateRequest(
    val id: PatchId,
    val name: String,
    val artifactName: String,
    val artifactVersion: String? = null,
    val appName: String,
    val versions: Set<String>,
    val target: PatchTarget = PatchTarget.AllNodes,
    val requirements: PatchRequirements = PatchRequirements(),
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

private fun ByteArray.patchRequirements(): PatchRequirements {
    return runCatching {
        JarInputStream(inputStream()).use { jar ->
            val attributes = jar.manifest?.mainAttributes ?: return PatchRequirements()
            PatchRequirements(
                roles = attributes.getValue(PATCH_ROLES_MANIFEST_ATTRIBUTE).toRoles(),
                modules = attributes.getValue(PATCH_MODULES_MANIFEST_ATTRIBUTE).toStringSet(),
                capabilities = attributes.getValue(PATCH_CAPABILITIES_MANIFEST_ATTRIBUTE).toStringSet(),
            )
        }
    }.getOrDefault(PatchRequirements())
}

private fun String?.toRoles(): Set<RoleKey> {
    return toStringSet().mapTo(linkedSetOf(), ::RoleKey)
}

private fun String?.toStringSet(): Set<String> {
    return this
        ?.split(',', ';')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toCollection(linkedSetOf())
        ?: emptySet()
}

private const val PATCH_ROLES_MANIFEST_ATTRIBUTE: String = "Asteria-Patch-Roles"
private const val PATCH_MODULES_MANIFEST_ATTRIBUTE: String = "Asteria-Patch-Modules"
private const val PATCH_CAPABILITIES_MANIFEST_ATTRIBUTE: String = "Asteria-Patch-Capabilities"
