package io.github.realmlabs.asteria.patch.zookeeper

import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import io.github.realmlabs.asteria.patch.PatchId
import io.github.realmlabs.asteria.patch.RuntimePatchNodeResult
import io.github.realmlabs.asteria.patch.RuntimePatchNodeResultQuery
import io.github.realmlabs.asteria.patch.RuntimePatchNodeResultRepository
import org.apache.curator.x.async.AsyncCuratorFramework
import org.slf4j.LoggerFactory

/**
 * ZooKeeper-backed [RuntimePatchNodeResultRepository].
 *
 * Results are append-only per patch/node attempt and are grouped by app/version for operator inspection. Attempt
 * numbers are allocated through a ZooKeeper compare-and-set counter keyed by patch id and node address.
 */
class ZookeeperRuntimePatchNodeResultRepository(
    private val client: AsyncCuratorFramework,
    rootPath: String = "/asteria/runtime-patches",
    private val codec: ZookeeperPatchCodec = JacksonZookeeperPatchCodec(),
    private val metrics: Metrics = NoopMetrics,
) : RuntimePatchNodeResultRepository {
    private val paths = ZookeeperPatchPaths(rootPath)
    private val zk = ZookeeperPatchClient(client)
    private val logger = LoggerFactory.getLogger(ZookeeperRuntimePatchNodeResultRepository::class.java)

    override suspend fun nextAttempt(
        patchId: PatchId,
        address: String,
    ): Int = measured("next_attempt") {
        zk.incrementCounter(paths.nodeResultAttemptCounterPath(patchId, address)).toInt()
    }

    override suspend fun save(result: RuntimePatchNodeResult) = measured("save") {
        val nodeKey = result.nodeId ?: result.address
        zk.upsert(
            paths.nodeResultPath(result.appName, result.version, result.patchId, nodeKey, result.attempt),
            codec.encodeNodeResult(result),
        )
    }

    override suspend fun list(query: RuntimePatchNodeResultQuery): List<RuntimePatchNodeResult> = measured("list") {
        scanResults(query)
            .filter { result -> query.patchId == null || result.patchId == query.patchId }
            .filter { result -> query.address == null || result.address == query.address }
            .filter { result -> query.status == null || result.status == query.status }
            .sortedWith(compareBy<RuntimePatchNodeResult> { it.patchId.value }.thenBy { it.address }
                .thenBy { it.attempt })
    }

    private suspend fun scanResults(query: RuntimePatchNodeResultQuery): List<RuntimePatchNodeResult> {
        return zk.children(paths.appsPath()).flatMap { appSegment ->
            val appName = ZookeeperPatchPaths.decodeSegment(appSegment)
            zk.children(paths.versionsPath(appSegment)).flatMap { versionSegment ->
                val version = ZookeeperPatchPaths.decodeSegment(versionSegment)
                val patchSegments = query.patchId?.let { listOf(it.value.segment()) }
                    ?: zk.children(paths.nodeResultsPath(appName, version))
                patchSegments.flatMap { patchSegment ->
                    val patchId = PatchId(ZookeeperPatchPaths.decodeSegment(patchSegment))
                    val patchPath = "${paths.nodeResultsPath(appName, version)}/$patchSegment"
                    zk.children(patchPath).flatMap { nodeSegment ->
                        val nodePath = "$patchPath/$nodeSegment"
                        zk.children(nodePath).mapNotNull { attempt ->
                            zk.read("$nodePath/$attempt")?.let(codec::decodeNodeResult)
                        }
                    }.filter { result -> query.patchId == null || result.patchId == patchId }
                }
            }
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
}
