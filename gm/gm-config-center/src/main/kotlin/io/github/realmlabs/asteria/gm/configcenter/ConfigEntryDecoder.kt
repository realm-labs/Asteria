package io.github.realmlabs.asteria.gm.configcenter

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Extension point for producing safe, bounded previews of raw ConfigStore entries.
 *
 * Decoder selection is explicit: [supports] declares whether a decoder owns an entry, and [order] gives deterministic
 * precedence when several decoders match. Exceptions are treated as decoder failures by [ConfigCenterBrowser] and never
 * prevent basic hash and size inspection.
 */
interface ConfigEntryDecoder {
    val id: String

    val order: Int
        get() = 0

    fun supports(context: ConfigEntryDecodeContext): Boolean

    fun decode(context: ConfigEntryDecodeContext): ConfigEntryPreview
}

/**
 * Built-in decoder for UTF-8 text and JSON entries.
 */
object Utf8ConfigEntryDecoder : ConfigEntryDecoder {
    override val id: String = "asteria.utf8"
    override val order: Int = 10_000

    override fun supports(context: ConfigEntryDecodeContext): Boolean {
        val text = context.bytes.decodeUtf8Strict() ?: return false
        return text.isLikelyText()
    }

    override fun decode(context: ConfigEntryDecodeContext): ConfigEntryPreview {
        val bytes = context.bytes
        val text = bytes.decodeToString()
        val previewBytes = bytes.copyOfRange(0, bytes.size.coerceAtMost(context.previewLimitBytes))
        val preview = previewBytes.decodeToString()
        val trimmed = text.trimStart()
        return ConfigEntryPreview(
            contentType = if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                "application/json"
            } else {
                "text/plain"
            },
            encoding = "utf-8",
            preview = preview,
            truncated = bytes.size > context.previewLimitBytes,
        )
    }
}

/**
 * Fallback decoder for entries that cannot be safely represented as text.
 */
object BinaryConfigEntryDecoder : ConfigEntryDecoder {
    private const val BINARY_PREVIEW_BYTES = 256

    override val id: String = "asteria.binary"
    override val order: Int = Int.MAX_VALUE

    override fun supports(context: ConfigEntryDecodeContext): Boolean = true

    override fun decode(context: ConfigEntryDecodeContext): ConfigEntryPreview {
        val previewSize = context.bytes.size.coerceAtMost(context.previewLimitBytes).coerceAtMost(BINARY_PREVIEW_BYTES)
        val preview = if (previewSize == 0) {
            null
        } else {
            Base64.getEncoder().encodeToString(context.bytes.copyOfRange(0, previewSize))
        }
        return ConfigEntryPreview(
            contentType = "application/octet-stream",
            encoding = "base64",
            preview = preview,
            truncated = context.bytes.size > previewSize,
        )
    }
}

private fun ByteArray.decodeUtf8Strict(): String? {
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return runCatching { decoder.decode(ByteBuffer.wrap(this)).toString() }.getOrNull()
}

private fun String.isLikelyText(): Boolean {
    return all { char ->
        char == '\n' || char == '\r' || char == '\t' || !char.isISOControl()
    }
}
