package io.github.realmlabs.asteria.config.publisher

import io.github.realmlabs.asteria.config.ConfigRevision
import io.github.realmlabs.asteria.config.ConfigTableName
import java.time.Instant

/**
 * Manifest written to the config center for one published config revision.
 *
 * The manifest is the stable audit record for a successful publication. It describes the validated snapshot revision,
 * the raw artifact files uploaded with it, and the runtime component builders that were executed during validation.
 */
data class ConfigPublicationManifest(
    val revision: ConfigRevision,
    val generatedAt: Instant,
    val tables: List<String>,
    val artifacts: List<ConfigPublicationArtifactManifest>,
    val components: List<ConfigPublicationComponentManifest> = emptyList(),
)

/**
 * Manifest entry for one raw artifact uploaded under a publication revision.
 *
 * [checksum] is a SHA-256 hex digest of the exact bytes stored in the config center. Consumers verify both [size] and
 * [checksum] before exposing a publication bundle to loaders.
 */
data class ConfigPublicationArtifactManifest(
    val path: String,
    val size: Long,
    val checksum: String,
)

/**
 * Runtime component metadata captured during publication validation.
 *
 * Dependencies use manifest table names. Consumers validate that every dependency is present in
 * [ConfigPublicationManifest.tables] before accepting the manifest, which catches stale or malformed publication
 * records before runtime loading begins.
 */
data class ConfigPublicationComponentManifest(
    val name: String,
    val type: String,
    val dependencies: List<String>,
) {
    init {
        require(name.isNotBlank()) { "config publication component name must not be blank" }
        require(type.isNotBlank()) { "config publication component type must not be blank" }
        require(dependencies.all { it.isNotBlank() }) {
            "config publication component dependency must not be blank"
        }
        require(dependencies.distinct().size == dependencies.size) {
            "config publication component dependencies must not contain duplicates"
        }
    }
}

/**
 * Pointer stored at the layout's current path after all artifacts and the manifest have been uploaded.
 */
data class CurrentConfigPublication(
    val revision: ConfigRevision,
    val manifestPath: String,
    val publishedAt: Instant,
)

/**
 * Lightweight history entry for one successful publication.
 *
 * The full manifest stays under the immutable revision path. History records are optimized for listing and operational
 * rollback decisions without reading every raw artifact.
 */
data class ConfigPublicationRecord(
    val revision: ConfigRevision,
    val manifestPath: String,
    val publishedAt: Instant,
    val artifactCount: Int,
    val totalArtifactBytes: Long,
)

internal fun ConfigTableName.asManifestName(): String = value
