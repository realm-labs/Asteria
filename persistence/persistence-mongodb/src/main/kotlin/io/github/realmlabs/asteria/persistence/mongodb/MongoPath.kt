package io.github.realmlabs.asteria.persistence.mongodb

import io.github.realmlabs.asteria.persistence.mongodb.MongoPath.Companion.encodePathPart


/**
 * Mongo document key used by the dirty write queue.
 */
data class MongoDocumentKey(
    val collection: String,
    val documentId: Any?,
) {
    init {
        require(collection.isNotBlank()) { "Mongo collection name must not be blank" }
    }
}

/**
 * Encoded Mongo field path inside a document.
 */
data class MongoPath(
    val collection: String,
    val documentId: Any?,
    val fieldPath: String,
) {
    init {
        require(collection.isNotBlank()) { "Mongo collection name must not be blank" }
        require(fieldPath.isNotBlank()) { "Mongo field path must not be blank" }
    }

    val key: MongoDocumentKey
        get() = MongoDocumentKey(collection, documentId)

    fun child(part: Any?): MongoPath {
        val childPath = encodePathPart(part)
        return copy(fieldPath = "$fieldPath.$childPath")
    }

    /**
     * Counts logical data segments for deciding when to fall back to boundary writes.
     */
    fun dataDepth(): Int {
        val parts = fieldPath.split(".")
        return if (parts.firstOrNull() == "data") parts.size - 1 else parts.size
    }

    companion object {
        /**
         * Encodes one logical field path segment into a Mongo update-safe segment.
         *
         * Dynamic map keys must go through this function before they are used in
         * `$set`/`$unset` paths. The encoding is deliberately percent-based and
         * escapes `%` first, so raw keys such as `a.b` and `a%2Eb` do not collapse
         * to the same stored field name. Empty keys are also encoded because an
         * empty update path segment would produce paths like `bag.`.
         */
        fun encodePathPart(part: Any?): String {
            val rawPart = when (part) {
                is Enum<*> -> part.name
                else -> part.toString()
            }
            if (rawPart.isEmpty()) return "%EMPTY"

            return buildString(rawPart.length) {
                rawPart.forEach { char ->
                    when (char) {
                        '%' -> append("%25")
                        '.' -> append("%2E")
                        '$' -> append("%24")
                        '\u0000' -> append("%00")
                        else -> append(char)
                    }
                }
            }
        }

        /**
         * Decodes a path segment produced by [encodePathPart].
         *
         * This is mainly useful for diagnostics and tests. Normal dirty-write
         * paths should stay encoded until Mongo applies the update.
         */
        fun decodePathPart(part: String): String {
            if (part == "%EMPTY") return ""

            return buildString(part.length) {
                var index = 0
                while (index < part.length) {
                    if (part[index] == '%' && index + 2 < part.length) {
                        when (part.substring(index + 1, index + 3).uppercase()) {
                            "25" -> {
                                append('%')
                                index += 3
                                continue
                            }

                            "2E" -> {
                                append('.')
                                index += 3
                                continue
                            }

                            "24" -> {
                                append('$')
                                index += 3
                                continue
                            }

                            "00" -> {
                                append('\u0000')
                                index += 3
                                continue
                            }
                        }
                    }
                    append(part[index])
                    index++
                }
            }
        }
    }
}

internal fun MongoDocumentKey.path(fieldPath: String): MongoPath {
    return MongoPath(collection, documentId, fieldPath)
}
