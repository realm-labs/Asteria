package io.github.realmlabs.asteria.config.publisher

import io.github.realmlabs.asteria.config.ConfigRevision
import io.github.realmlabs.asteria.config.center.*
import io.github.realmlabs.asteria.config.luban.MemoryLubanDataSource

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
    /**
     * Loads the revision referenced by the current pointer.
     *
     * The manifest revision and every artifact size/checksum are verified before the bundle is returned.
     */
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

    /**
     * Loads a specific immutable revision without consulting the current pointer.
     *
     * This is useful for promotion checks and diagnostics. It performs the same manifest and artifact validation as
     * [loadCurrent].
     */
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
        validateComponents(manifest)

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

    private fun validateComponents(manifest: ConfigPublicationManifest) {
        val tables = manifest.tables.toSet()
        for (component in manifest.components) {
            val missing = component.dependencies.filter { it !in tables }
            if (missing.isNotEmpty()) {
                throw ConfigPublicationValidationException(
                    "config component ${component.name} depends on missing tables: ${missing.joinToString()}",
                )
            }
        }
    }
}

/**
 * A validated publication and its raw artifacts.
 *
 * [artifacts] is keyed by manifest artifact path and contains defensive byte-array copies. [current] is `null` when the
 * bundle was loaded directly by revision.
 */
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
