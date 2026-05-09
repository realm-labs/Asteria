package io.github.realmlabs.asteria.patch.zookeeper

import io.github.realmlabs.asteria.patch.PatchArtifact
import io.github.realmlabs.asteria.patch.PatchId
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Version-scoped ZooKeeper path layout for runtime patches.
 *
 * Patch metadata and artifacts are grouped under app/version so operators can inspect one running game version without
 * scanning unrelated versions. Path segments are URL-safe base64 encoded; znode data keeps the original values.
 */
class ZookeeperPatchPaths(
    rootPath: String = "/asteria/runtime-patches",
) {
    val rootPath: String = normalizeRoot(rootPath)

    fun appVersionPath(
        appName: String,
        version: String,
    ): String {
        return "$rootPath/apps/${appName.segment()}/versions/${version.segment()}"
    }

    fun patchesPath(
        appName: String,
        version: String,
    ): String {
        return "${appVersionPath(appName, version)}/patches"
    }

    fun patchMetadataPath(
        appName: String,
        version: String,
        patchId: PatchId,
    ): String {
        return "${patchesPath(appName, version)}/${patchId.value.segment()}/metadata"
    }

    fun artifactsPath(
        appName: String,
        version: String,
    ): String {
        return "${appVersionPath(appName, version)}/artifacts"
    }

    fun artifactPath(
        appName: String,
        version: String,
        artifact: PatchArtifact,
    ): String {
        return "${artifactsPath(appName, version)}/${artifact.keySegment()}"
    }

    fun artifactMetadataPath(
        appName: String,
        version: String,
        artifact: PatchArtifact,
    ): String {
        return "${artifactPath(appName, version, artifact)}/metadata"
    }

    fun artifactContentPath(
        appName: String,
        version: String,
        artifact: PatchArtifact,
    ): String {
        return "${artifactPath(appName, version, artifact)}/content"
    }

    fun patchIndexPath(patchId: PatchId): String {
        return "$rootPath/index/patches/${patchId.value.segment()}"
    }

    fun patchRevisionCounterPath(): String {
        return "$rootPath/counters/patch-revision"
    }

    fun nodeResultAttemptCounterPath(
        patchId: PatchId,
        address: String,
    ): String {
        return "$rootPath/counters/node-results/${patchId.value.segment()}/${address.segment()}"
    }

    fun nodeResultsPath(
        appName: String,
        version: String,
    ): String {
        return "${appVersionPath(appName, version)}/node-results"
    }

    fun nodeResultPath(
        appName: String,
        version: String,
        patchId: PatchId,
        nodeKey: String,
        attempt: Int,
    ): String {
        return "${nodeResultsPath(appName, version)}/${patchId.value.segment()}/${nodeKey.segment()}/$attempt"
    }

    fun appsPath(): String {
        return "$rootPath/apps"
    }

    fun versionsPath(appNameSegment: String): String {
        return "${appsPath()}/$appNameSegment/versions"
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

    private fun normalizeRoot(path: String): String {
        val normalized = path.trim().trimEnd('/')
        require(normalized.startsWith("/")) { "zookeeper patch root path must start with /" }
        require(normalized.length > 1) { "zookeeper patch root path must not be root" }
        return normalized
    }

    companion object {
        fun encodeSegment(value: String): String {
            require(value.isNotBlank()) { "zookeeper patch path segment must not be blank" }
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
                    require(index + 2 < segment.length) { "invalid zookeeper patch path escape" }
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
