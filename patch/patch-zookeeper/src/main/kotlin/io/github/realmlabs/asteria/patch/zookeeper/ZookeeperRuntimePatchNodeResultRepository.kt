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

class ZookeeperRuntimePatchNodeResultRepository(
    private val client: AsyncCuratorFramework,
    rootPath: String = "/asteria/runtime-patches",
    private val metrics: Metrics = NoopMetrics,
) : RuntimePatchNodeResultRepository {
    private val paths = ZookeeperPatchPaths(rootPath)
    private val logger = LoggerFactory.getLogger(ZookeeperRuntimePatchNodeResultRepository::class.java)

    override suspend fun nextAttempt(
        patchId: PatchId,
        address: String,
    ): Int = measured("next_attempt") {
        incrementCounter(paths.nodeResultAttemptCounterPath(patchId, address)).toInt()
    }

    override suspend fun save(result: RuntimePatchNodeResult) = measured("save") {
        val nodeKey = result.nodeId ?: result.address
        upsert(
            paths.nodeResultPath(result.appName, result.version, result.patchId, nodeKey, result.attempt),
            result.encodeZnode(),
        )
    }

    override suspend fun list(query: RuntimePatchNodeResultQuery): List<RuntimePatchNodeResult> = measured("list") {
        scanResults(query)
            .filter { result -> query.patchId == null || result.patchId == query.patchId }
            .filter { result -> query.address == null || result.address == query.address }
            .filter { result -> query.status == null || result.status == query.status }
            .sortedWith(compareBy<RuntimePatchNodeResult> { it.patchId.value }.thenBy { it.address }.thenBy { it.attempt })
    }

    private suspend fun scanResults(query: RuntimePatchNodeResultQuery): List<RuntimePatchNodeResult> {
        return children(paths.appsPath()).flatMap { appSegment ->
            val appName = ZookeeperPatchPaths.decodeSegment(appSegment)
            children(paths.versionsPath(appSegment)).flatMap { versionSegment ->
                val version = ZookeeperPatchPaths.decodeSegment(versionSegment)
                val patchSegments = query.patchId?.let { listOf(it.value.segment()) }
                    ?: children("${paths.nodeResultsPath(appName, version)}")
                patchSegments.flatMap { patchSegment ->
                    val patchId = PatchId(ZookeeperPatchPaths.decodeSegment(patchSegment))
                    val patchPath = "${paths.nodeResultsPath(appName, version)}/$patchSegment"
                    children(patchPath).flatMap { nodeSegment ->
                        val nodePath = "$patchPath/$nodeSegment"
                        children(nodePath).mapNotNull { attempt ->
                            read("$nodePath/$attempt")?.decodeRuntimePatchNodeResult()
                        }
                    }.filter { result -> query.patchId == null || result.patchId == patchId }
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

    private suspend fun <T> measured(
        operation: String,
        block: suspend () -> T,
    ): T {
        val tags = MetricTags.of("backend" to "zookeeper", "operation" to operation)
        metrics.counter("asteria.patch.zookeeper.node_result.operation.total", tags).increment()
        val start = System.nanoTime()
        return try {
            block()
        } catch (error: Throwable) {
            metrics.counter("asteria.patch.zookeeper.node_result.operation.failed.total", tags).increment()
            logger.warn("zookeeper patch node result operation failed operation={}", operation, error)
            throw error
        } finally {
            metrics.timer("asteria.patch.zookeeper.node_result.operation.duration", tags)
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
