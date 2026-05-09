package io.github.realmlabs.asteria.patch

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Durable store for runtime patch metadata.
 *
 * Implementations assign [RuntimePatchDescriptor.revision] on first save and again when an existing descriptor is
 * replaced. Revisions are monotonically increasing and define patch layer precedence: newer descriptors override older
 * descriptors that replace the same registry slot.
 */
interface RuntimePatchRepository {
    suspend fun nextRevision(): Long

    suspend fun save(patch: RuntimePatchDescriptor): RuntimePatchDescriptor

    suspend fun find(id: PatchId): RuntimePatchDescriptor?

    suspend fun list(query: RuntimePatchQuery = RuntimePatchQuery()): List<RuntimePatchDescriptor>

    /**
     * Updates status and returns the new patch, or `null` if the patch no longer exists.
     */
    suspend fun updateStatus(id: PatchId, status: PatchStatus): RuntimePatchDescriptor?

    /**
     * Marks enabled patches for the same app as expired when they no longer match [environment].
     *
     * This is a best-effort scan/update loop, not a transaction across all matching patches.
     */
    suspend fun expireIncompatible(environment: PatchEnvironment): List<RuntimePatchDescriptor> {
        return list(
            RuntimePatchQuery(
                status = PatchStatus.Enabled,
                appName = environment.appName,
            ),
        )
            .filterNot { it.compatibility.matches(environment) }
            .mapNotNull { patch -> updateStatus(patch.id, PatchStatus.Expired) }
    }
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
    initialPatches: Iterable<RuntimePatchDescriptor> = emptyList(),
) : RuntimePatchRepository {
    private val lock = Mutex()
    private val patches: MutableMap<PatchId, RuntimePatchDescriptor> = linkedMapOf()
    private var revision: Long = 0

    init {
        initialPatches.forEach { patch ->
            val stored = if (patch.revision > 0) patch else patch.copy(revision = nextRevisionLocked())
            patches[stored.id] = stored
            revision = maxOf(revision, stored.revision)
        }
    }

    override suspend fun nextRevision(): Long {
        return lock.withLock {
            revision += 1
            revision
        }
    }

    override suspend fun save(patch: RuntimePatchDescriptor): RuntimePatchDescriptor {
        return lock.withLock {
            val existing = patches[patch.id]
            val stored = when {
                patch.revision <= 0 -> patch.copy(revision = nextRevisionLocked())
                existing != null && patch != existing -> patch.copy(revision = nextRevisionLocked())
                else -> patch
            }
            patches[stored.id] = stored
            revision = maxOf(revision, stored.revision)
            stored
        }
    }

    override suspend fun find(id: PatchId): RuntimePatchDescriptor? {
        return lock.withLock { patches[id] }
    }

    override suspend fun list(query: RuntimePatchQuery): List<RuntimePatchDescriptor> {
        return lock.withLock {
            patches.values
                .asSequence()
                .filter { patch -> query.status == null || patch.status == query.status }
                .filter { patch -> query.appName == null || patch.compatibility.appName == query.appName }
                .filter { patch -> query.version == null || query.version in patch.compatibility.versions }
                .sortedBy { it.revision }
                .toList()
        }
    }

    override suspend fun updateStatus(id: PatchId, status: PatchStatus): RuntimePatchDescriptor? {
        return lock.withLock {
            val patch = patches[id] ?: return@withLock null
            patch.copy(status = status).also { patches[id] = it }
        }
    }

    private fun nextRevisionLocked(): Long {
        revision += 1
        return revision
    }
}
