package io.github.realmlabs.asteria.gm.configcenter

import io.github.realmlabs.asteria.config.center.ConfigEntry
import io.github.realmlabs.asteria.config.center.ConfigPath
import io.github.realmlabs.asteria.config.center.ConfigStore
import io.github.realmlabs.asteria.config.center.configPath
import java.security.MessageDigest

/**
 * Read-only browser over the raw [ConfigStore] tree.
 */
class ConfigCenterBrowser(
    private val store: ConfigStore,
    private val accessPolicy: ConfigCenterBrowserAccessPolicy = ConfigCenterBrowserAccessPolicy(),
    decoders: Iterable<ConfigEntryDecoder> = emptyList(),
    private val previewLimitBytes: Int = DefaultPreviewLimitBytes,
) {
    private val decoders: List<ConfigEntryDecoder> = (decoders.toList() + listOf(Utf8ConfigEntryDecoder))
        .also { entries ->
            val duplicateId = entries.groupBy { it.id }.entries.firstOrNull { it.value.size > 1 }?.key
            require(duplicateId == null) { "duplicate config entry decoder id $duplicateId" }
        }
        .sortedWith(compareBy<ConfigEntryDecoder> { it.order }.thenBy { it.id })

    init {
        require(previewLimitBytes > 0) { "preview limit must be positive" }
    }

    /**
     * Returns current path metadata and direct children without recursively expanding descendants.
     */
    suspend fun tree(rawPath: String): GmConfigCenterTreeResponse {
        val path = normalizeReadablePath(rawPath)
        val entry = readValue(path)
        val children = readChildren(path).map { it.summary() }
        return GmConfigCenterTreeResponse(
            path = path.value,
            exists = entry != null,
            revision = entry?.revision?.version,
            revisionMetadata = entry?.revision?.metadata.orEmpty(),
            size = entry?.bytes?.size,
            children = children,
        )
    }

    /**
     * Returns a bounded preview of one entry, or an `exists=false` response when the path has no value.
     */
    suspend fun entry(rawPath: String): GmConfigCenterEntryResponse {
        val path = normalizeReadablePath(rawPath)
        val entry = readValue(path) ?: return GmConfigCenterEntryResponse(path = path.value, exists = false)
        val preview = decode(entry)
        return GmConfigCenterEntryResponse(
            path = path.value,
            exists = true,
            revision = entry.revision.version,
            revisionMetadata = entry.revision.metadata,
            size = entry.bytes.size,
            contentType = preview.contentType,
            encoding = preview.encoding,
            preview = preview.preview,
            truncated = preview.truncated,
            checksum = sha256(entry.bytes),
        )
    }

    private fun normalizeReadablePath(rawPath: String): ConfigPath {
        val path = try {
            configPath(rawPath)
        } catch (_: IllegalArgumentException) {
            throw ConfigCenterBrowserAccessException("config center path is not allowed")
        }
        accessPolicy.checkReadable(path)
        return path
    }

    private suspend fun readValue(path: ConfigPath): ConfigEntry? {
        return try {
            store.get(path)
        } catch (error: ConfigCenterBrowserException) {
            throw error
        } catch (error: Exception) {
            throw ConfigCenterBrowserUnavailableException("config center entry is unavailable", error)
        }
    }

    private suspend fun readChildren(path: ConfigPath): List<ConfigEntry> {
        return try {
            store.children(path)
        } catch (error: ConfigCenterBrowserException) {
            throw error
        } catch (error: Exception) {
            throw ConfigCenterBrowserUnavailableException("config center children are unavailable", error)
        }
    }

    private fun decode(entry: ConfigEntry): ConfigEntryPreview {
        val context = ConfigEntryDecodeContext(
            path = entry.path,
            bytes = entry.bytes,
            revision = entry.revision,
            previewLimitBytes = previewLimitBytes,
        )
        decoders
            .filter { decoder -> runCatching { decoder.supports(context) }.getOrDefault(false) }
            .forEach { decoder ->
                val preview = runCatching { decoder.decode(context) }.getOrNull()
                if (preview != null) {
                    return preview
                }
            }
        return BinaryConfigEntryDecoder.decode(context)
    }

    private fun ConfigEntry.summary(): GmConfigCenterEntrySummary {
        return GmConfigCenterEntrySummary(
            path = path.value,
            name = path.name,
            revision = revision.version,
            revisionMetadata = revision.metadata,
            size = bytes.size,
        )
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        const val DefaultPreviewLimitBytes: Int = 64 * 1024
    }
}

/**
 * Base class for browser failures with sanitized messages.
 */
sealed class ConfigCenterBrowserException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Raised when a path is outside the configured read boundary.
 */
class ConfigCenterBrowserAccessException(message: String) : ConfigCenterBrowserException(message)

/**
 * Raised when the backing ConfigStore cannot serve a read.
 */
class ConfigCenterBrowserUnavailableException(
    message: String,
    cause: Throwable? = null,
) : ConfigCenterBrowserException(message, cause)
