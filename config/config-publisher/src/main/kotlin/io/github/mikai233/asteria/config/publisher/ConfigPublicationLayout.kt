package io.github.mikai233.asteria.config.publisher

import io.github.mikai233.asteria.config.ConfigRevision
import io.github.mikai233.asteria.config.center.ConfigPath
import io.github.mikai233.asteria.config.center.configPath

/**
 * Config-center paths used by [ConfigPublisher].
 *
 * A publication writes immutable revision artifacts first, then advances [currentPath]. Consumers should watch the
 * current pointer instead of guessing which revision directory is newest.
 */
data class ConfigPublicationLayout(
    val root: ConfigPath = configPath("/asteria/config"),
) {
    val currentPath: ConfigPath = root / "current"
    val revisionsPath: ConfigPath = root / "revisions"

    fun revisionPath(revision: ConfigRevision): ConfigPath {
        return revisionsPath / revision.version.toConfigPathSegment()
    }

    fun manifestPath(revision: ConfigRevision): ConfigPath {
        return revisionPath(revision) / "manifest"
    }

    fun artifactsPath(revision: ConfigRevision): ConfigPath {
        return revisionPath(revision) / "artifacts"
    }

    fun artifactPath(
        revision: ConfigRevision,
        relativePath: String,
    ): ConfigPath {
        ConfigPublicationArtifact(relativePath, ByteArray(0))
        return artifactsPath(revision) / relativePath
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
