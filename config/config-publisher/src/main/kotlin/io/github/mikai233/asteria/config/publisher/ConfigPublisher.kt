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
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

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
) {
    suspend fun publish(): ConfigPublicationResult {
        val snapshot = loadAndValidate()
        val artifacts = artifactSource.artifacts().distinctArtifacts()
        val manifest = snapshot.toManifest(artifacts, Instant.now(clock))
        val repository = RuntimeConfigRepository(store, codec)

        val artifactStoreRevisions = linkedMapOf<String, ConfigRevision>()
        for (artifact in artifacts) {
            artifactStoreRevisions[artifact.relativePath] = store.put(
                path = layout.artifactPath(snapshot.revision, artifact.relativePath),
                bytes = artifact.bytes,
            )
        }
        val manifestStoreRevision = repository.put(layout.manifestPath(snapshot.revision), manifest)
        val currentStoreRevision = repository.put(
            layout.currentPath,
            CurrentConfigPublication(
                revision = snapshot.revision,
                manifestPath = layout.manifestPath(snapshot.revision).value,
                publishedAt = manifest.generatedAt,
            ),
        )

        return ConfigPublicationResult(
            snapshot = snapshot,
            manifest = manifest,
            manifestStoreRevision = manifestStoreRevision,
            currentStoreRevision = currentStoreRevision,
            artifactStoreRevisions = artifactStoreRevisions,
        )
    }

    private suspend fun loadAndValidate(): ConfigSnapshot {
        val service = ConfigService(loader, validators, componentBuilders)
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
}

data class ConfigPublicationResult(
    val snapshot: ConfigSnapshot,
    val manifest: ConfigPublicationManifest,
    val manifestStoreRevision: ConfigRevision,
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

private fun sha256(bytes: ByteArray): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
