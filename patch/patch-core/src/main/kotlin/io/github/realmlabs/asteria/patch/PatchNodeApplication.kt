package io.github.realmlabs.asteria.patch

import io.github.realmlabs.asteria.core.RoleKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Serializable
import java.time.Instant

data class PatchNode(
    val nodeId: String?,
    val address: String,
    val appName: String,
    val version: String,
    val roles: Set<RoleKey> = emptySet(),
) : Serializable {
    init {
        nodeId?.let { require(it.isNotBlank()) { "patch node id must not be blank" } }
        require(address.isNotBlank()) { "patch node address must not be blank" }
        require(appName.isNotBlank()) { "patch node app name must not be blank" }
        require(version.isNotBlank()) { "patch node version must not be blank" }
    }

    fun environment(): PatchEnvironment {
        return PatchEnvironment(
            appName = appName,
            version = version,
            nodeAddress = address,
            roles = roles,
        )
    }
}

fun interface PatchNodeProvider {
    suspend fun nodes(): List<PatchNode>
}

class LocalPatchNodeProvider(
    private val environment: PatchEnvironment,
) : PatchNodeProvider {
    override suspend fun nodes(): List<PatchNode> {
        return listOf(
            PatchNode(
                nodeId = null,
                address = environment.nodeAddress ?: "local",
                appName = environment.appName,
                version = environment.version,
                roles = environment.roles,
            ),
        )
    }
}

interface PatchNodeClient {
    suspend fun apply(
        node: PatchNode,
        patchId: PatchId,
    ): PatchApplyResult

    suspend fun disable(
        node: PatchNode,
        patchId: PatchId,
    ): Boolean
}

class LocalPatchNodeClient(
    private val service: PatchApplicationService,
) : PatchNodeClient {
    override suspend fun apply(
        node: PatchNode,
        patchId: PatchId,
    ): PatchApplyResult {
        return service.apply(patchId)
    }

    override suspend fun disable(
        node: PatchNode,
        patchId: PatchId,
    ): Boolean {
        return service.disable(patchId)
    }
}

interface RuntimePatchNodeResultRepository {
    suspend fun nextAttempt(
        patchId: PatchId,
        address: String,
    ): Int

    suspend fun save(result: RuntimePatchNodeResult)

    suspend fun list(query: RuntimePatchNodeResultQuery = RuntimePatchNodeResultQuery()): List<RuntimePatchNodeResult>
}

data class RuntimePatchNodeResultQuery(
    val patchId: PatchId? = null,
    val address: String? = null,
    val status: RuntimePatchNodeStatus? = null,
) : Serializable {
    init {
        address?.let { require(it.isNotBlank()) { "patch node result query address must not be blank" } }
    }
}

class InMemoryRuntimePatchNodeResultRepository : RuntimePatchNodeResultRepository {
    private val lock = Mutex()
    private val results: MutableList<RuntimePatchNodeResult> = mutableListOf()

    override suspend fun nextAttempt(
        patchId: PatchId,
        address: String,
    ): Int {
        return lock.withLock {
            results.count { it.patchId == patchId && it.address == address } + 1
        }
    }

    override suspend fun save(result: RuntimePatchNodeResult) {
        lock.withLock {
            results += result
        }
    }

    override suspend fun list(query: RuntimePatchNodeResultQuery): List<RuntimePatchNodeResult> {
        return lock.withLock {
            results
                .asSequence()
                .filter { query.patchId == null || it.patchId == query.patchId }
                .filter { query.address == null || it.address == query.address }
                .filter { query.status == null || it.status == query.status }
                .sortedWith(compareBy<RuntimePatchNodeResult> { it.patchId.value }.thenBy { it.address }
                    .thenBy { it.attempt })
                .toList()
        }
    }
}

data class RuntimePatchNodeResult(
    val patchId: PatchId,
    val nodeId: String?,
    val address: String,
    val appName: String,
    val version: String,
    val roles: Set<RoleKey> = emptySet(),
    val status: RuntimePatchNodeStatus,
    val attempt: Int,
    val operationCount: Int? = null,
    val message: String? = null,
    val updatedAt: Instant = Instant.now(),
) : Serializable {
    init {
        nodeId?.let { require(it.isNotBlank()) { "patch node result node id must not be blank" } }
        require(address.isNotBlank()) { "patch node result address must not be blank" }
        require(appName.isNotBlank()) { "patch node result app name must not be blank" }
        require(version.isNotBlank()) { "patch node result version must not be blank" }
        require(attempt > 0) { "patch node result attempt must be positive" }
        operationCount?.let { require(it >= 0) { "patch node result operation count must not be negative" } }
        message?.let { require(it.isNotBlank()) { "patch node result message must not be blank" } }
    }
}

enum class RuntimePatchNodeStatus {
    Applied,
    Removed,
    Ignored,
    Failed,
}

data class PatchClusterApplyResult(
    val patchId: PatchId,
    val requestedAt: Instant,
    val results: List<RuntimePatchNodeResult>,
) : Serializable {
    val succeeded: Boolean = results.isNotEmpty() && results.all { it.status != RuntimePatchNodeStatus.Failed }
}

class PatchClusterApplicationService(
    private val repository: RuntimePatchRepository,
    private val nodes: PatchNodeProvider,
    private val client: PatchNodeClient,
    private val results: RuntimePatchNodeResultRepository = InMemoryRuntimePatchNodeResultRepository(),
) {
    suspend fun apply(id: PatchId): PatchClusterApplyResult {
        val patch = requireNotNull(repository.find(id)) { "patch $id not found" }
        val selected = nodes.nodes().filter { patch.canApplyTo(it.environment()) }
        val requestedAt = Instant.now()
        val nodeResults = coroutineScope {
            selected.map { node ->
                async { applyToNode(patch.id, node) }
            }.awaitAll()
        }.sortedBy { it.address }
        return PatchClusterApplyResult(patch.id, requestedAt, nodeResults)
    }

    suspend fun applyEnabledPatches(): List<PatchClusterApplyResult> {
        val availableNodes = nodes.nodes()
        val patches = repository.list(RuntimePatchQuery(status = PatchStatus.Enabled))
            .filter { patch -> availableNodes.any { node -> patch.canApplyTo(node.environment()) } }
        return patches.sortedBy { it.revision }.map { apply(it.id) }
    }

    suspend fun disable(id: PatchId): PatchClusterApplyResult {
        val patch = requireNotNull(repository.find(id)) { "patch $id not found" }
        val selected = nodes.nodes()
            .filter { patch.compatibility.matches(it.environment()) && patch.target.matches(it.environment()) }
        val requestedAt = Instant.now()
        val nodeResults = coroutineScope {
            selected.map { node ->
                async { disableOnNode(patch.id, node) }
            }.awaitAll()
        }.sortedBy { it.address }
        return PatchClusterApplyResult(patch.id, requestedAt, nodeResults)
    }

    suspend fun results(query: RuntimePatchNodeResultQuery = RuntimePatchNodeResultQuery()): List<RuntimePatchNodeResult> {
        return results.list(query)
    }

    private suspend fun applyToNode(
        patchId: PatchId,
        node: PatchNode,
    ): RuntimePatchNodeResult {
        val attempt = results.nextAttempt(patchId, node.address)
        val result = runCatching {
            client.apply(node, patchId)
        }.fold(
            onSuccess = { it.toNodeResult(patchId, node, attempt) },
            onFailure = { error ->
                RuntimePatchNodeResult(
                    patchId = patchId,
                    nodeId = node.nodeId,
                    address = node.address,
                    appName = node.appName,
                    version = node.version,
                    roles = node.roles,
                    status = RuntimePatchNodeStatus.Failed,
                    attempt = attempt,
                    message = error.message ?: error::class.qualifiedName ?: "unknown",
                )
            },
        )
        results.save(result)
        return result
    }

    private suspend fun disableOnNode(
        patchId: PatchId,
        node: PatchNode,
    ): RuntimePatchNodeResult {
        val attempt = results.nextAttempt(patchId, node.address)
        val result = runCatching {
            client.disable(node, patchId)
        }.fold(
            onSuccess = { removed ->
                RuntimePatchNodeResult(
                    patchId = patchId,
                    nodeId = node.nodeId,
                    address = node.address,
                    appName = node.appName,
                    version = node.version,
                    roles = node.roles,
                    status = if (removed) RuntimePatchNodeStatus.Removed else RuntimePatchNodeStatus.Ignored,
                    attempt = attempt,
                    message = if (removed) null else "patch was not applied on node",
                )
            },
            onFailure = { error ->
                RuntimePatchNodeResult(
                    patchId = patchId,
                    nodeId = node.nodeId,
                    address = node.address,
                    appName = node.appName,
                    version = node.version,
                    roles = node.roles,
                    status = RuntimePatchNodeStatus.Failed,
                    attempt = attempt,
                    message = error.message ?: error::class.qualifiedName ?: "unknown",
                )
            },
        )
        results.save(result)
        return result
    }
}

private fun PatchApplyResult.toNodeResult(
    patchId: PatchId,
    node: PatchNode,
    attempt: Int,
): RuntimePatchNodeResult {
    return when (this) {
        is PatchApplyResult.Applied -> RuntimePatchNodeResult(
            patchId = patchId,
            nodeId = node.nodeId,
            address = node.address,
            appName = node.appName,
            version = node.version,
            roles = node.roles,
            status = RuntimePatchNodeStatus.Applied,
            attempt = attempt,
            operationCount = operationCount,
        )

        is PatchApplyResult.Ignored -> RuntimePatchNodeResult(
            patchId = patchId,
            nodeId = node.nodeId,
            address = node.address,
            appName = node.appName,
            version = node.version,
            roles = node.roles,
            status = RuntimePatchNodeStatus.Ignored,
            attempt = attempt,
            message = reason,
        )
    }
}
