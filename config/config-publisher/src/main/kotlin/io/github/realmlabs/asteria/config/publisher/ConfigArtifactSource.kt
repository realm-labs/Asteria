package io.github.realmlabs.asteria.config.publisher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import kotlin.streams.asSequence

/**
 * One raw config artifact that should be published with a validated config snapshot.
 *
 * The [relativePath] is stored under the snapshot's artifact root in the config center. It must be a stable relative
 * path such as `items.bytes` or `activity/tasks.json`; absolute paths and parent traversal are rejected by the
 * publisher.
 */
data class ConfigPublicationArtifact(
    val relativePath: String,
    val bytes: ByteArray,
) {
    init {
        require(relativePath.isNotBlank()) { "config artifact path must not be blank" }
        require(!relativePath.startsWith("/")) { "config artifact path must be relative: $relativePath" }
        require(!relativePath.endsWith("/")) { "config artifact path must not end with slash: $relativePath" }
        require(!relativePath.contains("//")) { "config artifact path must not contain empty segments: $relativePath" }
        require(relativePath.split('/').none { it == "." || it == ".." }) {
            "config artifact path must not contain dot segments: $relativePath"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConfigPublicationArtifact) return false
        return relativePath == other.relativePath && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = relativePath.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

fun interface ConfigArtifactSource {
    /**
     * Returns the complete artifact set for one publication attempt.
     *
     * Implementations should return stable relative paths and stable bytes for the duration of a publish. Duplicate
     * paths are rejected by [ConfigPublisher].
     */
    suspend fun artifacts(): List<ConfigPublicationArtifact>
}

/**
 * Publishes every regular file under [root] as a config artifact.
 *
 * Traversal is recursive. [include] receives each visited regular file path, results are sorted by path string, and
 * artifact paths use `/` separators relative to [root].
 */
class DirectoryConfigArtifactSource(
    private val root: Path,
    private val include: (Path) -> Boolean = { true },
) : ConfigArtifactSource {
    override suspend fun artifacts(): List<ConfigPublicationArtifact> {
        return withContext(Dispatchers.IO) {
            require(Files.isDirectory(root)) { "config artifact root must be a directory: $root" }
            Files.walk(root).use { stream ->
                stream.asSequence()
                    .filter { it.isRegularFile() }
                    .filter(include)
                    .sortedBy { it.toString() }
                    .map { path ->
                        ConfigPublicationArtifact(
                            relativePath = path.relativeTo(root).normalize().toString()
                                .replace(path.fileSystem.separator, "/"),
                            bytes = Files.readAllBytes(path),
                        )
                    }
                    .toList()
            }
        }
    }
}
