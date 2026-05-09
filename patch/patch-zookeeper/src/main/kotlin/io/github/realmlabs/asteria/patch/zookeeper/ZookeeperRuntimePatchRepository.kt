package io.github.realmlabs.asteria.patch.zookeeper

import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import io.github.realmlabs.asteria.patch.*
import kotlinx.coroutines.future.await
import org.apache.curator.x.async.AsyncCuratorFramework
import org.apache.curator.x.async.api.CreateOption
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.data.Stat
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets.UTF_8

class ZookeeperRuntimePatchRepository(
    private val client: AsyncCuratorFramework,
    rootPath: String = "/asteria/runtime-patches",
    private val metrics: Metrics = NoopMetrics,
) : RuntimePatchRepository {
    private val paths = ZookeeperPatchPaths(rootPath)
    private val logger = LoggerFactory.getLogger(ZookeeperRuntimePatchRepository::class.java)

    override suspend fun nextSequence(): Long = measured("next_sequence") {
        incrementCounter(paths.patchSequenceCounterPath())
    }

    override suspend fun save(patch: RuntimePatch) = measured("save") {
        val oldIndex = read(paths.patchIndexPath(patch.id))?.decodePatchIndex()
        oldIndex?.versions
            ?.filter { version -> oldIndex.appName != patch.compatibility.appName || version !in patch.compatibility.versions }
            ?.forEach { version -> deleteIfExists(paths.patchMetadataPath(oldIndex.appName, version, patch.id)) }

        patch.compatibility.versions.forEach { version ->
            upsert(paths.patchMetadataPath(patch.compatibility.appName, version, patch.id), patch.encodeZnode())
        }
        upsert(paths.patchIndexPath(patch.id), encodePatchIndex(patch.compatibility.appName, patch.compatibility.versions))
    }

    override suspend fun find(id: PatchId): RuntimePatch? = measured("find") {
        val index = read(paths.patchIndexPath(id))?.decodePatchIndex() ?: return@measured null
        for (version in index.versions) {
            read(paths.patchMetadataPath(index.appName, version, id))?.decodeRuntimePatch()?.let { patch ->
                return@measured patch
            }
        }
        null
    }

    override suspend fun list(query: RuntimePatchQuery): List<RuntimePatch> = measured("list") {
        scanPatches(query)
            .filter { patch -> query.status == null || patch.status == query.status }
            .filter { patch -> query.appName == null || patch.compatibility.appName == query.appName }
            .filter { patch -> query.version == null || query.version in patch.compatibility.versions }
            .distinctBy { it.id }
            .sortedBy { it.order }
    }

    override suspend fun updateStatus(
        id: PatchId,
        status: PatchStatus,
    ): RuntimePatch? = measured("update_status") {
        val patch = find(id) ?: return@measured null
        patch.copy(status = status).also { save(it) }
    }

    private suspend fun scanPatches(query: RuntimePatchQuery): List<RuntimePatch> {
        val appSegments = query.appName?.let { listOf(it.segment()) } ?: children(paths.appsPath())
        return appSegments.flatMap { appSegment ->
            val appName = ZookeeperPatchPaths.decodeSegment(appSegment)
            if (query.appName != null && appName != query.appName) return@flatMap emptyList()
            val versionSegments = query.version?.let { listOf(it.segment()) } ?: children(paths.versionsPath(appSegment))
            versionSegments.flatMap { versionSegment ->
                val version = ZookeeperPatchPaths.decodeSegment(versionSegment)
                if (query.version != null && version != query.version) return@flatMap emptyList()
                children(paths.patchesPath(appName, version)).mapNotNull { patchSegment ->
                    val patchId = PatchId(ZookeeperPatchPaths.decodeSegment(patchSegment))
                    read(paths.patchMetadataPath(appName, version, patchId))?.decodeRuntimePatch()
                }
            }
        }
    }

    private suspend fun incrementCounter(path: String): Long {
        while (true) {
            val current = readWithStat(path)
            if (current == null) {
                try {
                    upsert(path, "1".toByteArray(UTF_8), createOnly = true)
                    return 1
                } catch (_: KeeperException.NodeExistsException) {
                    continue
                }
            }
            val next = current.bytes.toString(UTF_8).toLong() + 1
            try {
                client.setData()
                    .withVersion(current.version)
                    .forPath(path, next.toString().toByteArray(UTF_8))
                    .await()
                return next
            } catch (_: KeeperException.BadVersionException) {
                continue
            } catch (_: KeeperException.NoNodeException) {
                continue
            }
        }
    }

    private suspend fun upsert(
        path: String,
        bytes: ByteArray,
        createOnly: Boolean = false,
    ) {
        try {
            client.create()
                .withOptions(setOf(CreateOption.createParentsIfNeeded))
                .forPath(path, bytes)
                .await()
        } catch (error: KeeperException.NodeExistsException) {
            if (createOnly) throw error
            client.setData()
                .forPath(path, bytes)
                .await()
        }
    }

    private suspend fun read(path: String): ByteArray? {
        return try {
            client.data.forPath(path).await()
        } catch (_: KeeperException.NoNodeException) {
            null
        }
    }

    private suspend fun readWithStat(path: String): ZnodeBytes? {
        return try {
            val stat = Stat()
            ZnodeBytes(client.data.storingStatIn(stat).forPath(path).await(), stat.version)
        } catch (_: KeeperException.NoNodeException) {
            null
        }
    }

    private suspend fun children(path: String): List<String> {
        return try {
            client.children.forPath(path).await().sorted()
        } catch (_: KeeperException.NoNodeException) {
            emptyList()
        }
    }

    private suspend fun deleteIfExists(path: String) {
        try {
            client.delete().forPath(path).await()
        } catch (_: KeeperException.NoNodeException) {
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

    private data class ZnodeBytes(
        val bytes: ByteArray,
        val version: Int,
    )
}
