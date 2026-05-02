package io.github.mikai233.asteria.config.publisher

import io.github.mikai233.asteria.config.ConfigRevision
import io.github.mikai233.asteria.config.ConfigTableName
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

data class ConfigPublicationArtifactManifest(
    val path: String,
    val size: Long,
    val checksum: String,
)

data class ConfigPublicationComponentManifest(
    val name: String,
    val type: String,
    val dependencies: List<String>,
)

/**
 * Pointer stored at the layout's current path after all artifacts and the manifest have been uploaded.
 */
data class CurrentConfigPublication(
    val revision: ConfigRevision,
    val manifestPath: String,
    val publishedAt: Instant,
)

internal fun ConfigTableName.asManifestName(): String = value
