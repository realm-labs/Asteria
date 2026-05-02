package io.github.mikai233.asteria.config.publisher

import io.github.mikai233.asteria.config.ConfigComponentBuilder
import io.github.mikai233.asteria.config.ConfigLoader
import io.github.mikai233.asteria.config.ConfigService
import io.github.mikai233.asteria.config.ConfigSnapshot
import io.github.mikai233.asteria.config.ConfigValidator
import io.github.mikai233.asteria.config.center.ConfigCodec
import io.github.mikai233.asteria.config.center.ConfigRevision
import io.github.mikai233.asteria.config.center.ConfigStore
import io.github.mikai233.asteria.config.center.JacksonConfigCodec
import io.github.mikai233.asteria.config.center.RuntimeConfigRepository
import io.github.mikai233.asteria.observability.Metrics
import io.github.mikai233.asteria.observability.NoopMetrics
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import org.slf4j.LoggerFactory

/**
 * Validates a config export and publishes its raw artifacts to a config center.
 *
 * The publisher intentionally works after a game's export tool has produced files. For a Luban project, the flow is:
 *
 * 1. Run Luban export.
 * 2. Load the exported files through a [ConfigLoader].
 * 3. Build runtime components and run validators.
 * 4. Upload raw files and a manifest.
 * 5. Move the current pointer to the new revision.
 */
class ConfigPublisher(
    private val loader: ConfigLoader,
    private val artifactSource: ConfigArtifactSource,
    private val store: ConfigStore,
    private val layout: ConfigPublicationLayout = ConfigPublicationLayout(),
    private val codec: ConfigCodec = JacksonConfigCodec(),
    private val validators: List<ConfigValidator> = emptyList(),
    private val componentBuilders: List<ConfigComponentBuilder<*>> = emptyList(),
    private val clock: Clock = Clock.systemUTC(),
    private val metrics: Metrics = NoopMetrics,
) {
    private val logger = LoggerFactory.getLogger(ConfigPublisher::class.java)

    suspend fun publish(): ConfigPublicationResult {
        val startedAt = System.nanoTime()
        metrics.counter("asteria.config.publisher.publish.total").increment()
        try {
            val snapshot = loadAndValidate()
            val artifacts = artifactSource.artifacts().distinctArtifacts()
            val manifest = snapshot.toManifest(artifacts, Instant.now(clock))
            val repository = RuntimeConfigRepository(store, codec, metrics)

            val artifactStoreRevisions = linkedMapOf<String, ConfigRevision>()
            for (artifact in artifacts) {
                artifactStoreRevisions[artifact.relativePath] = store.put(
                    path = layout.artifactPath(snapshot.revision, artifact.relativePath),
                    bytes = artifact.bytes,
                )
                metrics.counter("asteria.config.publisher.artifact.published.total").increment()
                metrics.counter("asteria.config.publisher.artifact.bytes.total").increment(artifact.bytes.size.toLong())
            }
            val manifestStoreRevision = repository.put(layout.manifestPath(snapshot.revision), manifest)
            val recordStoreRevision = repository.put(layout.historyRecordPath(snapshot.revision), manifest.toRecord())
            val currentStoreRevision = repository.put(
                layout.currentPath,
                CurrentConfigPublication(
                    revision = snapshot.revision,
                    manifestPath = layout.manifestPath(snapshot.revision).value,
                    publishedAt = manifest.generatedAt,
                ),
            )

            metrics.counter("asteria.config.publisher.publish.succeeded.total").increment()
            logger.info(
                "config published revision={} artifacts={} bytes={}",
                snapshot.revision.version,
                artifacts.size,
                artifacts.sumOf { it.bytes.size },
            )
            return ConfigPublicationResult(
                snapshot = snapshot,
                manifest = manifest,
                manifestStoreRevision = manifestStoreRevision,
                recordStoreRevision = recordStoreRevision,
                currentStoreRevision = currentStoreRevision,
                artifactStoreRevisions = artifactStoreRevisions,
            )
        } catch (error: Throwable) {
            metrics.counter("asteria.config.publisher.publish.failed.total").increment()
            logger.error("config publish failed", error)
            throw error
        } finally {
            metrics.timer("asteria.config.publisher.publish.duration")
                .record((System.nanoTime() - startedAt) / 1_000_000)
        }
    }

    private suspend fun loadAndValidate(): ConfigSnapshot {
        val service = ConfigService(loader, validators, componentBuilders, metrics = metrics)
        return service.load().current
    }

    private fun ConfigSnapshot.toManifest(
        artifacts: List<ConfigPublicationArtifact>,
        generatedAt: Instant,
    ): ConfigPublicationManifest {
        return ConfigPublicationManifest(
            revision = revision,
            generatedAt = generatedAt,
            tables = tables().map { it.name.asManifestName() }.sorted(),
            artifacts = artifacts.map { artifact ->
                ConfigPublicationArtifactManifest(
                    path = artifact.relativePath,
                    size = artifact.bytes.size.toLong(),
                    checksum = sha256(artifact.bytes),
                )
            },
            components = componentBuilders.map { builder ->
                ConfigPublicationComponentManifest(
                    name = builder.name,
                    type = builder.type.qualifiedName ?: builder.type.simpleName ?: builder.name,
                    dependencies = builder.dependencies.map { it.asManifestName() }.sorted(),
                )
            },
        )
    }

    private fun ConfigPublicationManifest.toRecord(): ConfigPublicationRecord {
        return ConfigPublicationRecord(
            revision = revision,
            manifestPath = layout.manifestPath(revision).value,
            publishedAt = generatedAt,
            artifactCount = artifacts.size,
            totalArtifactBytes = artifacts.sumOf { it.size },
        )
    }
}

data class ConfigPublicationResult(
    val snapshot: ConfigSnapshot,
    val manifest: ConfigPublicationManifest,
    val manifestStoreRevision: ConfigRevision,
    val recordStoreRevision: ConfigRevision,
    val currentStoreRevision: ConfigRevision,
    val artifactStoreRevisions: Map<String, ConfigRevision>,
)

private fun List<ConfigPublicationArtifact>.distinctArtifacts(): List<ConfigPublicationArtifact> {
    val seen = mutableSetOf<String>()
    return map { artifact ->
        require(seen.add(artifact.relativePath)) { "duplicate config artifact ${artifact.relativePath}" }
        artifact
    }
}

internal fun sha256(bytes: ByteArray): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
