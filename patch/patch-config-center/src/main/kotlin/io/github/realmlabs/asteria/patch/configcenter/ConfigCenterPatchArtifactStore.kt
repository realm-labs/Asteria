package io.github.realmlabs.asteria.patch.configcenter

import io.github.realmlabs.asteria.config.center.ConfigStore
import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import io.github.realmlabs.asteria.patch.*
import org.slf4j.LoggerFactory

/**
 * Version-scoped config-center artifact store.
 *
 * This store is intended for small patch jars. Larger deployments can keep patch metadata in config-center and use
 * another [PatchArtifactStore] for artifact bytes.
 */
class ConfigCenterPatchArtifactStore(
    store: ConfigStore,
    private val appName: String,
    private val appVersion: String,
    rootPath: String = "/asteria/runtime-patches",
    private val codec: ConfigCenterPatchCodec = JacksonConfigCenterPatchCodec(),
    private val maxArtifactBytes: Int = DEFAULT_MAX_ARTIFACT_BYTES,
    private val metrics: Metrics = NoopMetrics,
) : WritablePatchArtifactStore {
    private val paths = ConfigCenterPatchPaths(rootPath)
    private val client = ConfigCenterPatchClient(store)
    private val logger = LoggerFactory.getLogger(ConfigCenterPatchArtifactStore::class.java)

    init {
        require(appName.isNotBlank()) { "config-center patch artifact app name must not be blank" }
        require(appVersion.isNotBlank()) { "config-center patch artifact app version must not be blank" }
        require(maxArtifactBytes > 0) { "config-center patch artifact max bytes must be positive" }
    }

    override suspend fun save(
        name: String,
        bytes: ByteArray,
        version: String?,
    ): PatchArtifact = measured("save", bytes.size.toLong()) {
        require(bytes.size <= maxArtifactBytes) {
            "patch artifact $name is too large for config-center: ${bytes.size} > $maxArtifactBytes bytes"
        }
        val artifact = PatchArtifact(
            name = name.safeArtifactName(),
            checksum = patchArtifactSha256Checksum(bytes),
            version = version,
        )
        client.upsert(paths.artifactMetadataPath(appName, appVersion, artifact), codec.encodeArtifact(artifact))
        client.upsert(paths.artifactContentPath(appName, appVersion, artifact), bytes.copyOf())
        artifact
    }

    override suspend fun load(artifact: PatchArtifact): ByteArray = measured("load") {
        val stored = client.read(paths.artifactMetadataPath(appName, appVersion, artifact))?.let(codec::decodeArtifact)
        require(stored == null || stored == artifact) {
            "config-center patch artifact metadata mismatch for ${artifact.name}"
        }
        val bytes = requireNotNull(client.read(paths.artifactContentPath(appName, appVersion, artifact))) {
            "config-center patch artifact ${artifact.name} with checksum ${artifact.checksum} not found for $appName:$appVersion"
        }
        verifyPatchArtifactChecksum(artifact, bytes)
        bytes.copyOf()
    }

    private suspend fun <T> measured(
        operation: String,
        bytes: Long? = null,
        block: suspend () -> T,
    ): T {
        val tags = MetricTags.of("backend" to "config-center", "operation" to operation)
        metrics.counter("asteria.patch.config_center.artifact.operation.total", tags).increment()
        bytes?.let { metrics.counter("asteria.patch.config_center.artifact.bytes.total", tags).increment(it) }
        val start = System.nanoTime()
        return try {
            block()
        } catch (error: Throwable) {
            metrics.counter("asteria.patch.config_center.artifact.operation.failed.total", tags).increment()
            logger.warn("config-center patch artifact operation failed operation={}", operation, error)
            throw error
        } finally {
            metrics.timer("asteria.patch.config_center.artifact.operation.duration", tags)
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
