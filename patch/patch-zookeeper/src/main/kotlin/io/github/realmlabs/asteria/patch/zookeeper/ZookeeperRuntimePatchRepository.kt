package io.github.realmlabs.asteria.patch.zookeeper

import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import io.github.realmlabs.asteria.patch.*
import org.apache.curator.x.async.AsyncCuratorFramework
import org.slf4j.LoggerFactory

/**
 * ZooKeeper-backed [RuntimePatchRepository].
 *
 * Patch metadata is stored under app/version paths so each node only scans the version it is running. When a patch is
 * compatible with several versions, [save] writes one metadata znode per version and updates a small patch-id index for
 * direct [find] lookups. The metadata payload is encoded by [codec], while path segment escaping is handled by
 * [ZookeeperPatchPaths].
 */
class ZookeeperRuntimePatchRepository(
    private val client: AsyncCuratorFramework,
    rootPath: String = "/asteria/runtime-patches",
    private val codec: ZookeeperPatchCodec = JacksonZookeeperPatchCodec(),
    private val metrics: Metrics = NoopMetrics,
) : RuntimePatchRepository {
    private val paths = ZookeeperPatchPaths(rootPath)
    private val zk = ZookeeperPatchClient(client)
    private val logger = LoggerFactory.getLogger(ZookeeperRuntimePatchRepository::class.java)

    override suspend fun nextRevision(): Long = measured("next_revision") {
        zk.incrementCounter(paths.patchRevisionCounterPath())
    }

    override suspend fun save(patch: RuntimePatchDescriptor): RuntimePatchDescriptor = measured("save") {
        val existing = find(patch.id)
        val stored = when {
            patch.revision <= 0 -> patch.copy(revision = nextRevision())
            existing != null && patch != existing -> patch.copy(revision = nextRevision())
            else -> patch
        }
        val oldIndex = zk.read(paths.patchIndexPath(patch.id))?.let(codec::decodePatchIndex)
        oldIndex?.versions
            ?.filter { version ->
                oldIndex.appName != stored.compatibility.appName || version !in stored.compatibility.versions
            }
            ?.forEach { version -> zk.deleteIfExists(paths.patchMetadataPath(oldIndex.appName, version, stored.id)) }

        stored.compatibility.versions.forEach { version ->
            zk.upsert(
                paths.patchMetadataPath(stored.compatibility.appName, version, stored.id),
                codec.encodePatch(stored)
            )
        }
        zk.upsert(
            paths.patchIndexPath(stored.id),
            codec.encodePatchIndex(ZookeeperPatchIndex(stored.compatibility.appName, stored.compatibility.versions)),
        )
        stored
    }

    override suspend fun find(id: PatchId): RuntimePatchDescriptor? = measured("find") {
        val index = zk.read(paths.patchIndexPath(id))?.let(codec::decodePatchIndex) ?: return@measured null
        for (version in index.versions) {
            zk.read(paths.patchMetadataPath(index.appName, version, id))?.let(codec::decodePatch)?.let { patch ->
                return@measured patch
            }
        }
        null
    }

    override suspend fun list(query: RuntimePatchQuery): List<RuntimePatchDescriptor> = measured("list") {
        scanPatches(query)
            .asSequence()
            .filter { patch -> query.status == null || patch.status == query.status }
            .filter { patch -> query.appName == null || patch.compatibility.appName == query.appName }
            .filter { patch -> query.version == null || query.version in patch.compatibility.versions }
            .distinctBy { it.id }
            .sortedBy { it.revision }
            .toList()
    }

    override suspend fun updateStatus(
        id: PatchId,
        status: PatchStatus,
    ): RuntimePatchDescriptor? = measured("update_status") {
        val patch = find(id) ?: return@measured null
        patch.copy(status = status).also { updated ->
            updated.compatibility.versions.forEach { version ->
                zk.upsert(
                    paths.patchMetadataPath(updated.compatibility.appName, version, updated.id),
                    codec.encodePatch(updated),
                )
            }
        }
    }

    private suspend fun scanPatches(query: RuntimePatchQuery): List<RuntimePatchDescriptor> {
        val appSegments = query.appName?.let { listOf(it.segment()) } ?: zk.children(paths.appsPath())
        return appSegments.flatMap { appSegment ->
            val appName = ZookeeperPatchPaths.decodeSegment(appSegment)
            if (query.appName != null && appName != query.appName) return@flatMap emptyList()
            val versionSegments = query.version?.let { listOf(it.segment()) } ?: zk.children(paths.versionsPath(appSegment))
            versionSegments.flatMap { versionSegment ->
                val version = ZookeeperPatchPaths.decodeSegment(versionSegment)
                if (query.version != null && version != query.version) return@flatMap emptyList()
                zk.children(paths.patchesPath(appName, version)).mapNotNull { patchSegment ->
                    val patchId = PatchId(ZookeeperPatchPaths.decodeSegment(patchSegment))
                    zk.read(paths.patchMetadataPath(appName, version, patchId))?.let(codec::decodePatch)
                }
            }
        }
    }

    private suspend fun <T> measured(
        operation: String,
        block: suspend () -> T,
    ): T {
        val tags = MetricTags.of("backend" to "zookeeper", "operation" to operation)
        metrics.counter("asteria.patch.zookeeper.repository.operation.total", tags).increment()
        val start = System.nanoTime()
        return try {
            block()
        } catch (error: Throwable) {
            metrics.counter("asteria.patch.zookeeper.repository.operation.failed.total", tags).increment()
            logger.warn("zookeeper patch repository operation failed operation={}", operation, error)
            throw error
        } finally {
            metrics.timer("asteria.patch.zookeeper.repository.operation.duration", tags)
                .record((System.nanoTime() - start) / 1_000_000)
        }
    }

    private fun String.segment(): String {
        return ZookeeperPatchPaths.encodeSegment(this)
    }
}
