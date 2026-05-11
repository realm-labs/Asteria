package io.github.realmlabs.asteria.patch.configcenter

import io.github.realmlabs.asteria.config.center.ConfigStore
import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import io.github.realmlabs.asteria.patch.*
import org.slf4j.LoggerFactory

/**
 * Config-center backed [RuntimePatchNodeResultRepository].
 *
 * Results are append-only direct children under one app/version path so every config-center backend can list them with
 * a direct child scan.
 */
class ConfigCenterRuntimePatchNodeResultRepository(
    store: ConfigStore,
    rootPath: String = "/asteria/runtime-patches",
    private val codec: ConfigCenterPatchCodec = JacksonConfigCenterPatchCodec(),
    private val metrics: Metrics = NoopMetrics,
) : RuntimePatchNodeResultRepository {
    private val paths = ConfigCenterPatchPaths(rootPath)
    private val client = ConfigCenterPatchClient(store)
    private val logger = LoggerFactory.getLogger(ConfigCenterRuntimePatchNodeResultRepository::class.java)

    override suspend fun nextAttempt(
        patchId: PatchId,
        address: String,
    ): Int = measured("next_attempt") {
        client.incrementCounter(paths.nodeResultAttemptCounterPath(patchId, address)).toInt()
    }

    override suspend fun save(result: RuntimePatchNodeResult) = measured("save") {
        val nodeKey = result.nodeId ?: result.address
        client.upsert(
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
        val patches = query.patchId?.let { listOf(it) } ?: patchIdsFromIndex()
        return patches.flatMap { patchId ->
            val patch = patchMetadata(patchId) ?: return@flatMap emptyList()
            patch.compatibility.versions.flatMap { version ->
                client.children(paths.nodeResultsPath(patch.compatibility.appName, version))
                    .map { entry -> codec.decodeNodeResult(entry.bytes) }
            }
        }
    }

    private suspend fun patchIdsFromIndex(): List<PatchId> {
        return client.children(paths.patchIndexRootPath())
            .map { entry -> PatchId(ConfigCenterPatchPaths.decodeSegment(entry.path.name)) }
    }

    private suspend fun patchMetadata(patchId: PatchId): RuntimePatchDescriptor? {
        val index = client.read(paths.patchIndexPath(patchId))?.let(codec::decodePatchIndex) ?: return null
        return index.versions.firstNotNullOfOrNull { version ->
            client.read(paths.patchMetadataPath(index.appName, version, patchId))?.let(codec::decodePatch)
        }
    }

    private suspend fun <T> measured(
        operation: String,
        block: suspend () -> T,
    ): T {
        val tags = MetricTags.of("backend" to "config-center", "operation" to operation)
        metrics.counter("asteria.patch.config_center.node_result.operation.total", tags).increment()
        val start = System.nanoTime()
        return try {
            block()
        } catch (error: Throwable) {
            metrics.counter("asteria.patch.config_center.node_result.operation.failed.total", tags).increment()
            logger.warn("config-center patch node result operation failed operation={}", operation, error)
            throw error
        } finally {
            metrics.timer("asteria.patch.config_center.node_result.operation.duration", tags)
                .record((System.nanoTime() - start) / 1_000_000)
        }
    }
}
