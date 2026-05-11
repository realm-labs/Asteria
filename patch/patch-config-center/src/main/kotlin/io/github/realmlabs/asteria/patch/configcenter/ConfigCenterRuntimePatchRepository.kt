package io.github.realmlabs.asteria.patch.configcenter

import io.github.realmlabs.asteria.config.center.ConfigStore
import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import io.github.realmlabs.asteria.patch.*
import org.slf4j.LoggerFactory

/**
 * Config-center backed [RuntimePatchRepository].
 *
 * The repository uses only [ConfigStore] operations, so the same implementation works with ZooKeeper, Nacos, Etcd, and
 * in-memory config-center stores.
 */
class ConfigCenterRuntimePatchRepository(
    store: ConfigStore,
    rootPath: String = "/asteria/runtime-patches",
    private val codec: ConfigCenterPatchCodec = JacksonConfigCenterPatchCodec(),
    private val metrics: Metrics = NoopMetrics,
) : RuntimePatchRepository {
    private val paths = ConfigCenterPatchPaths(rootPath)
    private val client = ConfigCenterPatchClient(store)
    private val logger = LoggerFactory.getLogger(ConfigCenterRuntimePatchRepository::class.java)

    override suspend fun nextRevision(): Long = measured("next_revision") {
        client.incrementCounter(paths.patchRevisionCounterPath())
    }

    override suspend fun save(patch: RuntimePatchDescriptor): RuntimePatchDescriptor = measured("save") {
        val existing = find(patch.id)
        val stored = when {
            patch.revision <= 0 -> patch.copy(revision = nextRevision())
            existing != null && patch != existing -> patch.copy(revision = nextRevision())
            else -> patch
        }
        val oldIndex = client.read(paths.patchIndexPath(patch.id))?.let(codec::decodePatchIndex)
        oldIndex?.versions
            ?.filter { version ->
                oldIndex.appName != stored.compatibility.appName || version !in stored.compatibility.versions
            }
            ?.forEach { version ->
                client.deleteIfExists(paths.patchMetadataPath(oldIndex.appName, version, stored.id))
            }

        stored.compatibility.versions.forEach { version ->
            client.upsert(
                paths.patchMetadataPath(stored.compatibility.appName, version, stored.id),
                codec.encodePatch(stored),
            )
        }
        client.upsert(
            paths.patchIndexPath(stored.id),
            codec.encodePatchIndex(ConfigCenterPatchIndex(stored.compatibility.appName, stored.compatibility.versions)),
        )
        stored
    }

    override suspend fun find(id: PatchId): RuntimePatchDescriptor? = measured("find") {
        val index = client.read(paths.patchIndexPath(id))?.let(codec::decodePatchIndex) ?: return@measured null
        for (version in index.versions) {
            client.read(paths.patchMetadataPath(index.appName, version, id))?.let(codec::decodePatch)?.let { patch ->
                return@measured patch
            }
        }
        null
    }

    override suspend fun list(query: RuntimePatchQuery): List<RuntimePatchDescriptor> = measured("list") {
        client.children(paths.patchIndexRootPath())
            .mapNotNull { entry ->
                val index = codec.decodePatchIndex(entry.bytes)
                if (query.appName != null && index.appName != query.appName) {
                    return@mapNotNull null
                }
                val versions = query.version?.let { version -> index.versions.filter { it == version } } ?: index.versions
                versions.firstNotNullOfOrNull { version ->
                    val patchId = PatchId(ConfigCenterPatchPaths.decodeSegment(entry.path.name))
                    client.read(paths.patchMetadataPath(index.appName, version, patchId))?.let(codec::decodePatch)
                }
            }
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
                client.upsert(
                    paths.patchMetadataPath(updated.compatibility.appName, version, updated.id),
                    codec.encodePatch(updated),
                )
            }
        }
    }

    private suspend fun <T> measured(
        operation: String,
        block: suspend () -> T,
    ): T {
        val tags = MetricTags.of("backend" to "config-center", "operation" to operation)
        metrics.counter("asteria.patch.config_center.repository.operation.total", tags).increment()
        val start = System.nanoTime()
        return try {
            block()
        } catch (error: Throwable) {
            metrics.counter("asteria.patch.config_center.repository.operation.failed.total", tags).increment()
            logger.warn("config-center patch repository operation failed operation={}", operation, error)
            throw error
        } finally {
            metrics.timer("asteria.patch.config_center.repository.operation.duration", tags)
                .record((System.nanoTime() - start) / 1_000_000)
        }
    }
}
