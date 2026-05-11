package io.github.realmlabs.asteria.patch.zookeeper

import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import io.github.realmlabs.asteria.patch.*
import kotlinx.coroutines.future.await
import org.apache.curator.x.async.AsyncCuratorFramework
import org.apache.curator.x.async.api.CreateOption
import org.apache.zookeeper.KeeperException
import org.slf4j.LoggerFactory

/**
 * Version-scoped ZooKeeper artifact store.
 *
 * This store is intended for small patch jars. Larger deployments can keep [ZookeeperRuntimePatchRepository] for
 * desired state and use another [PatchArtifactStore] implementation for bytes. The store is intentionally scoped to a
 * single app/version so jar znodes stay beside the metadata that references them.
 */
class ZookeeperPatchArtifactStore(
    private val client: AsyncCuratorFramework,
    private val appName: String,
    private val appVersion: String,
    rootPath: String = "/asteria/runtime-patches",
    private val codec: ZookeeperPatchCodec = JacksonZookeeperPatchCodec(),
    private val maxArtifactBytes: Int = DEFAULT_MAX_ARTIFACT_BYTES,
    private val metrics: Metrics = NoopMetrics,
) : WritablePatchArtifactStore {
    private val paths = ZookeeperPatchPaths(rootPath)
    private val logger = LoggerFactory.getLogger(ZookeeperPatchArtifactStore::class.java)

    init {
        require(appName.isNotBlank()) { "zookeeper patch artifact app name must not be blank" }
        require(appVersion.isNotBlank()) { "zookeeper patch artifact app version must not be blank" }
        require(maxArtifactBytes > 0) { "zookeeper patch artifact max bytes must be positive" }
    }

    override suspend fun save(
        name: String,
        bytes: ByteArray,
        version: String?,
    ): PatchArtifact = measured("save", bytes.size.toLong()) {
        require(bytes.size <= maxArtifactBytes) {
            "patch artifact $name is too large for ZooKeeper: ${bytes.size} > $maxArtifactBytes bytes"
        }
        val artifact = PatchArtifact(
            name = name.safeArtifactName(),
            checksum = patchArtifactSha256Checksum(bytes),
            version = version
        )
        upsert(paths.artifactMetadataPath(appName, appVersion, artifact), codec.encodeArtifact(artifact))
        upsert(paths.artifactContentPath(appName, appVersion, artifact), bytes.copyOf())
        artifact
    }

    override suspend fun load(artifact: PatchArtifact): ByteArray = measured("load") {
        val stored = read(paths.artifactMetadataPath(appName, appVersion, artifact))?.let(codec::decodeArtifact)
        require(stored == null || stored == artifact) {
            "zookeeper patch artifact metadata mismatch for ${artifact.name}"
        }
        val bytes = requireNotNull(read(paths.artifactContentPath(appName, appVersion, artifact))) {
            "zookeeper patch artifact ${artifact.name} with checksum ${artifact.checksum} not found for $appName:$appVersion"
        }
        verifyPatchArtifactChecksum(artifact, bytes)
        bytes.copyOf()
    }

    private suspend fun upsert(
        path: String,
        bytes: ByteArray,
    ) {
        try {
            client.create()
                .withOptions(setOf(CreateOption.createParentsIfNeeded))
                .forPath(path, bytes)
                .await()
        } catch (_: KeeperException.NodeExistsException) {
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

    private suspend fun <T> measured(
        operation: String,
        bytes: Long? = null,
        block: suspend () -> T,
    ): T {
        val tags = MetricTags.of("backend" to "zookeeper", "operation" to operation)
        metrics.counter("asteria.patch.zookeeper.artifact.operation.total", tags).increment()
        bytes?.let { metrics.counter("asteria.patch.zookeeper.artifact.bytes.total", tags).increment(it) }
        val start = System.nanoTime()
        return try {
            block()
        } catch (error: Throwable) {
            metrics.counter("asteria.patch.zookeeper.artifact.operation.failed.total", tags).increment()
            logger.warn("zookeeper patch artifact operation failed operation={}", operation, error)
            throw error
        } finally {
            metrics.timer("asteria.patch.zookeeper.artifact.operation.duration", tags)
                .record((System.nanoTime() - start) / 1_000_000)
        }
    }

    private fun String.safeArtifactName(): String {
        val raw = substringAfterLast('/').substringAfterLast('\\')
        require(raw.isNotBlank()) { "patch artifact file name must not be blank" }
        require('/' !in raw && '\\' !in raw) { "patch artifact file name must not contain path separators" }
        return raw
    }
}

private const val DEFAULT_MAX_ARTIFACT_BYTES: Int = 768 * 1024
