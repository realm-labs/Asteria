package io.github.mikai233.asteria.persistence.mongodb

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

    fun dataDepth(): Int {
        val parts = fieldPath.split(".")
        return if (parts.firstOrNull() == "data") parts.size - 1 else parts.size
    }

    companion object {
        /**
         * Encodes path segments that are unsafe in Mongo field names.
         */
        fun encodePathPart(part: Any?): String {
            val rawPart = when (part) {
                is Enum<*> -> part.name
                else -> part.toString()
            }
            return rawPart
                .replace("%", "%25")
                .replace(".", "%2E")
                .replace("$", "%24")
        }
    }
}

internal fun MongoDocumentKey.path(fieldPath: String): MongoPath {
    return MongoPath(collection, documentId, fieldPath)
}
