package io.github.mikai233.asteria.patch

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface RuntimePatchRepository {
    suspend fun nextSequence(): Long

    suspend fun save(patch: RuntimePatch)

    suspend fun find(id: PatchId): RuntimePatch?

    suspend fun list(query: RuntimePatchQuery = RuntimePatchQuery()): List<RuntimePatch>

    suspend fun updateStatus(id: PatchId, status: PatchStatus): RuntimePatch?
}

data class RuntimePatchQuery(
    val status: PatchStatus? = null,
    val appName: String? = null,
    val version: String? = null,
) {
    init {
        appName?.let { require(it.isNotBlank()) { "patch query app name must not be blank" } }
        version?.let { require(it.isNotBlank()) { "patch query version must not be blank" } }
    }
}

class InMemoryRuntimePatchRepository(
    initialPatches: Iterable<RuntimePatch> = emptyList(),
) : RuntimePatchRepository {
    private val lock = Mutex()
    private val patches: MutableMap<PatchId, RuntimePatch> = linkedMapOf()
    private var sequence: Long = 0

    init {
        initialPatches.forEach { patch ->
            patches[patch.id] = patch
            sequence = maxOf(sequence, patch.sequence)
        }
    }

    override suspend fun nextSequence(): Long {
        return lock.withLock {
            sequence += 1
            sequence
        }
    }

    override suspend fun save(patch: RuntimePatch) {
        lock.withLock {
            patches[patch.id] = patch
            sequence = maxOf(sequence, patch.sequence)
        }
    }

    override suspend fun find(id: PatchId): RuntimePatch? {
        return lock.withLock { patches[id] }
    }

    override suspend fun list(query: RuntimePatchQuery): List<RuntimePatch> {
        return lock.withLock {
            patches.values
                .asSequence()
                .filter { patch -> query.status == null || patch.status == query.status }
                .filter { patch -> query.appName == null || patch.compatibility.appName == query.appName }
                .filter { patch -> query.version == null || query.version in patch.compatibility.versions }
                .sortedBy { it.order }
                .toList()
        }
    }

    override suspend fun updateStatus(id: PatchId, status: PatchStatus): RuntimePatch? {
        return lock.withLock {
            val patch = patches[id] ?: return@withLock null
            patch.copy(status = status).also { patches[id] = it }
        }
    }
}
