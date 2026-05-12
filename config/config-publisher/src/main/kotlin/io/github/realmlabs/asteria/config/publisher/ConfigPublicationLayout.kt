package io.github.realmlabs.asteria.config.publisher

import io.github.realmlabs.asteria.config.ConfigRevision
import io.github.realmlabs.asteria.config.center.ConfigPath
import io.github.realmlabs.asteria.config.center.configPath

/**
 * Config-center paths used by [ConfigPublisher].
 *
 * A publication writes immutable revision artifacts first, then advances [currentPath]. Consumers should watch the
 * current pointer instead of guessing which revision directory is newest.
 */
data class ConfigPublicationLayout(
    val root: ConfigPath = configPath("/asteria/config"),
) {
    /**
     * Mutable pointer to the revision that runtime consumers should load.
     */
    val currentPath: ConfigPath = root / "current"

    /**
     * Root for immutable per-revision manifests and raw artifacts.
     */
    val revisionsPath: ConfigPath = root / "revisions"

    /**
     * Root for lightweight publication records used by operational listing and pruning.
     */
    val historyPath: ConfigPath = root / "history"

    /**
     * Directory path for immutable data belonging to [revision].
     */
    fun revisionPath(revision: ConfigRevision): ConfigPath {
        return revisionsPath / revision.version.toConfigPathSegment()
    }

    /**
     * Path of the manifest for [revision].
     */
    fun manifestPath(revision: ConfigRevision): ConfigPath {
        return revisionPath(revision) / "manifest"
    }

    /**
     * Root path containing raw artifacts for [revision].
     */
    fun artifactsPath(revision: ConfigRevision): ConfigPath {
        return revisionPath(revision) / "artifacts"
    }

    /**
     * Path of one raw artifact under [revision].
     *
     * [relativePath] is validated with [ConfigPublicationArtifact] path rules before it is appended.
     */
    fun artifactPath(
        revision: ConfigRevision,
        relativePath: String,
    ): ConfigPath {
        ConfigPublicationArtifact(relativePath, ByteArray(0))
        return artifactsPath(revision) / relativePath
    }

    /**
     * Path of the lightweight history record for [revision].
     */
    fun historyRecordPath(revision: ConfigRevision): ConfigPath {
        return historyPath / revision.version.toConfigPathSegment()
    }
}

private fun String.toConfigPathSegment(): String {
    require(isNotBlank()) { "config revision path segment must not be blank" }
    return buildString(length) {
        this@toConfigPathSegment.forEach { char ->
            when (char) {
                '%' -> append("%25")
                '/' -> append("%2F")
                else -> append(char)
            }
        }
    }
}
