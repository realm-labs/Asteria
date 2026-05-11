package io.github.realmlabs.asteria.patch.configcenter

import io.github.realmlabs.asteria.config.center.ConfigPath
import io.github.realmlabs.asteria.config.center.configPath
import io.github.realmlabs.asteria.patch.PatchArtifact
import io.github.realmlabs.asteria.patch.PatchId
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Config-center path layout for runtime patches.
 *
 * Patch metadata is stored directly under `patches/{patchId}` so all [ConfigStore][io.github.realmlabs.asteria.config.center.ConfigStore]
 * implementations can list it through direct-child reads without relying on backend-specific directory znodes.
 */
class ConfigCenterPatchPaths(
    rootPath: String = "/asteria/runtime-patches",
) {
    val rootPath: ConfigPath = configPath(rootPath)

    fun appVersionPath(
        appName: String,
        version: String,
    ): ConfigPath {
        return rootPath / "apps" / appName.segment() / "versions" / version.segment()
    }

    fun patchesPath(
        appName: String,
        version: String,
    ): ConfigPath {
        return appVersionPath(appName, version) / "patches"
    }

    fun patchMetadataPath(
        appName: String,
        version: String,
        patchId: PatchId,
    ): ConfigPath {
        return patchesPath(appName, version) / patchId.value.segment()
    }

    fun artifactsPath(
        appName: String,
        version: String,
    ): ConfigPath {
        return appVersionPath(appName, version) / "artifacts"
    }

    fun artifactPath(
        appName: String,
        version: String,
        artifact: PatchArtifact,
    ): ConfigPath {
        return artifactsPath(appName, version) / artifact.keySegment()
    }

    fun artifactMetadataPath(
        appName: String,
        version: String,
        artifact: PatchArtifact,
    ): ConfigPath {
        return artifactPath(appName, version, artifact) / "metadata"
    }

    fun artifactContentPath(
        appName: String,
        version: String,
        artifact: PatchArtifact,
    ): ConfigPath {
        return artifactPath(appName, version, artifact) / "content"
    }

    fun patchIndexRootPath(): ConfigPath {
        return rootPath / "index" / "patches"
    }

    fun patchIndexPath(patchId: PatchId): ConfigPath {
        return patchIndexRootPath() / patchId.value.segment()
    }

    fun patchRevisionCounterPath(): ConfigPath {
        return rootPath / "counters" / "patch-revision"
    }

    fun nodeResultAttemptCounterPath(
        patchId: PatchId,
        address: String,
    ): ConfigPath {
        return rootPath / "counters" / "node-results" / patchId.value.segment() / address.segment()
    }

    fun nodeResultsPath(
        appName: String,
        version: String,
    ): ConfigPath {
        return appVersionPath(appName, version) / "node-results"
    }

    fun nodeResultPath(
        appName: String,
        version: String,
        patchId: PatchId,
        nodeKey: String,
        attempt: Int,
    ): ConfigPath {
        return nodeResultsPath(appName, version) / "${patchId.value.segment()}__${nodeKey.segment()}__$attempt"
    }

    private fun PatchArtifact.keySegment(): String {
        val readable = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "artifact" }
            .take(MAX_READABLE_ARTIFACT_NAME)
        val checksum = checksum.removePrefix("sha256:").lowercase()
        val suffix = checksum.take(ARTIFACT_CHECKSUM_PREFIX_LENGTH).ifBlank { "unknown" }
        return "${readable}__$suffix".segment()
    }

    private fun String.segment(): String {
        return encodeSegment(this)
    }

    companion object {
        fun encodeSegment(value: String): String {
            require(value.isNotBlank()) { "config-center patch path segment must not be blank" }
            return buildString {
                value.toByteArray(UTF_8).forEach { byte ->
                    val char = byte.toInt().toChar()
                    if (char.isSafePathChar()) {
                        append(char)
                    } else {
                        append('%')
                        append((byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
                    }
                }
            }
        }

        fun decodeSegment(segment: String): String {
            val bytes = mutableListOf<Byte>()
            var index = 0
            while (index < segment.length) {
                if (segment[index] == '%') {
                    require(index + 2 < segment.length) { "invalid config-center patch path escape" }
                    bytes += segment.substring(index + 1, index + 3).toInt(16).toByte()
                    index += 3
                } else {
                    bytes += segment[index].code.toByte()
                    index += 1
                }
            }
            return String(bytes.toByteArray(), UTF_8)
        }

        private fun Char.isSafePathChar(): Boolean {
            return this in 'A'..'Z' ||
                    this in 'a'..'z' ||
                    this in '0'..'9' ||
                    this == '.' ||
                    this == '_' ||
                    this == '-'
        }
    }
}

private const val MAX_READABLE_ARTIFACT_NAME: Int = 48
private const val ARTIFACT_CHECKSUM_PREFIX_LENGTH: Int = 12
