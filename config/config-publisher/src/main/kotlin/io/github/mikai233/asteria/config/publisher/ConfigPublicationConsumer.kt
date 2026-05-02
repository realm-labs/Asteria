package io.github.mikai233.asteria.config.publisher

import io.github.mikai233.asteria.config.ConfigRevision
import io.github.mikai233.asteria.config.center.ConfigCodec
import io.github.mikai233.asteria.config.center.ConfigPath
import io.github.mikai233.asteria.config.center.ConfigStore
import io.github.mikai233.asteria.config.center.JacksonConfigCodec
import io.github.mikai233.asteria.config.center.RuntimeConfigRepository
import io.github.mikai233.asteria.config.luban.MemoryLubanDataSource

/**
 * Reads the current published config revision from a config center and validates all referenced artifacts.
 *
 * Consumers intentionally start from [ConfigPublicationLayout.currentPath]. This keeps runtime reloads aligned with the
 * publisher's two-phase write order: immutable revision data is written first, then the current pointer moves last.
 */
class ConfigPublicationConsumer(
    private val store: ConfigStore,
    private val layout: ConfigPublicationLayout = ConfigPublicationLayout(),
    private val codec: ConfigCodec = JacksonConfigCodec(),
) {
    suspend fun loadCurrent(): ConfigPublicationBundle {
        val repository = RuntimeConfigRepository(store, codec)
        val current = repository.get<CurrentConfigPublication>(layout.currentPath)?.value
            ?: throw ConfigPublicationNotFoundException(layout.currentPath)
        val manifestPath = ConfigPath(current.manifestPath)
        return load(
            revision = current.revision,
            manifestPath = manifestPath,
            current = current,
        )
    }

    suspend fun loadRevision(revision: ConfigRevision): ConfigPublicationBundle {
        return load(
            revision = revision,
            manifestPath = layout.manifestPath(revision),
            current = null,
        )
    }

    private suspend fun load(
        revision: ConfigRevision,
        manifestPath: ConfigPath,
        current: CurrentConfigPublication?,
    ): ConfigPublicationBundle {
        val repository = RuntimeConfigRepository(store, codec)
        val manifest = repository.get<ConfigPublicationManifest>(manifestPath)?.value
            ?: throw ConfigPublicationNotFoundException(manifestPath)
        if (manifest.revision != revision) {
            throw ConfigPublicationValidationException(
                "config revision $revision points to manifest ${manifestPath.value} with revision ${manifest.revision}",
            )
        }

        val artifacts = linkedMapOf<String, ByteArray>()
        for (artifact in manifest.artifacts) {
            ConfigPublicationArtifact(artifact.path, ByteArray(0))
            val path = layout.artifactPath(manifest.revision, artifact.path)
            val entry = store.get(path) ?: throw ConfigPublicationNotFoundException(path)
            val bytes = entry.bytes
            if (bytes.size.toLong() != artifact.size) {
                throw ConfigPublicationValidationException(
                    "config artifact ${artifact.path} size mismatch, expected=${artifact.size}, actual=${bytes.size}",
                )
            }
            val checksum = sha256(bytes)
            if (checksum != artifact.checksum) {
                throw ConfigPublicationValidationException(
                    "config artifact ${artifact.path} checksum mismatch, expected=${artifact.checksum}, actual=$checksum",
                )
            }
            artifacts[artifact.path] = bytes.copyOf()
        }

        return ConfigPublicationBundle(
            current = current,
            manifest = manifest,
            artifacts = artifacts,
        )
    }
}

data class ConfigPublicationBundle(
    val current: CurrentConfigPublication?,
    val manifest: ConfigPublicationManifest,
    val artifacts: Map<String, ByteArray>,
) {
    fun lubanDataSource(): MemoryLubanDataSource {
        return MemoryLubanDataSource(artifacts)
    }
}

class ConfigPublicationNotFoundException(
    val path: ConfigPath,
) : IllegalStateException("config publication entry not found at $path")

class ConfigPublicationValidationException(
    message: String,
) : IllegalStateException(message)
