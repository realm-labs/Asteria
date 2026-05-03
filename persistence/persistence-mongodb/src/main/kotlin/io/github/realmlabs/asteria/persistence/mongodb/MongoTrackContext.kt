package io.github.realmlabs.asteria.persistence.mongodb

import io.github.realmlabs.asteria.persistence.DataLease

/**
 * Context passed to generated or hand-written tracked document wrappers.
 */
data class MongoTrackContext(
    val collection: String,
    val documentId: Any?,
    val queue: MongoChangeQueue,
    val fieldRoot: String = "",
    val leaseProvider: () -> DataLease? = { null },
) {
    init {
        require(collection.isNotBlank()) { "Mongo collection name must not be blank" }
        require(fieldRoot.indexOf('.') < 0) { "Mongo field root must be a single field name" }
    }

    fun path(fieldName: String): MongoPath {
        require(fieldName.isNotBlank()) { "Mongo tracked field name must not be blank" }
        val field = MongoPath.encodePathPart(fieldName)
        val path = if (fieldRoot.isBlank()) {
            field
        } else {
            "${MongoPath.encodePathPart(fieldRoot)}.$field"
        }
        return MongoPath(collection, documentId, path)
    }

    fun ensureActive() {
        leaseProvider()?.ensureActive()
    }

    fun <T> trackedValue(fieldName: String, initialValue: T): MongoTrackedValue<T> {
        return MongoTrackedValue(path(fieldName), initialValue, queue, leaseProvider = leaseProvider)
    }
}

/**
 * Runtime contract for a tracked Mongo document wrapper.
 */
interface MongoTrackedDocument<ID : Any, E> : MongoPersistentValue {
    val id: ID

    fun toEntity(): E
}
