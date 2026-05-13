package io.github.realmlabs.asteria.gm.configcenter

import io.github.realmlabs.asteria.config.center.ConfigPath
import io.github.realmlabs.asteria.config.center.ConfigRevision

/**
 * Summary of one raw ConfigStore entry.
 */
data class GmConfigCenterEntrySummary(
    val path: String,
    val name: String,
    val revision: String,
    val revisionMetadata: Map<String, String> = emptyMap(),
    val size: Int,
)

/**
 * Direct children and optional value metadata for a ConfigStore path.
 */
data class GmConfigCenterTreeResponse(
    val path: String,
    val exists: Boolean,
    val revision: String? = null,
    val revisionMetadata: Map<String, String> = emptyMap(),
    val size: Int? = null,
    val children: List<GmConfigCenterEntrySummary> = emptyList(),
)

/**
 * Safe preview response for one raw ConfigStore entry.
 */
data class GmConfigCenterEntryResponse(
    val path: String,
    val exists: Boolean,
    val revision: String? = null,
    val revisionMetadata: Map<String, String> = emptyMap(),
    val size: Int? = null,
    val contentType: String? = null,
    val encoding: String? = null,
    val preview: String? = null,
    val truncated: Boolean = false,
    val checksum: String? = null,
)

/**
 * Input passed to a [ConfigEntryDecoder].
 */
data class ConfigEntryDecodeContext(
    val path: ConfigPath,
    val bytes: ByteArray,
    val revision: ConfigRevision,
    val previewLimitBytes: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is ConfigEntryDecodeContext) {
            return false
        }
        return path == other.path &&
                bytes.contentEquals(other.bytes) &&
                revision == other.revision &&
                previewLimitBytes == other.previewLimitBytes
    }

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + revision.hashCode()
        result = 31 * result + previewLimitBytes
        return result
    }
}

/**
 * Preview produced by a [ConfigEntryDecoder].
 */
data class ConfigEntryPreview(
    val contentType: String,
    val encoding: String? = null,
    val preview: String? = null,
    val truncated: Boolean = false,
)
