package io.github.realmlabs.asteria.persistence.mongodb.tracked

import io.github.realmlabs.asteria.persistence.DataLease
import io.github.realmlabs.asteria.persistence.mongodb.common.MongoPath
import io.github.realmlabs.asteria.persistence.mongodb.common.MongoPersistentValue
import io.github.realmlabs.asteria.persistence.mongodb.write.MongoChangeQueue

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

    /**
     * Builds a Mongo update path for a generated wrapper property.
     */
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

    /**
     * Fails if the owning row or data unit has been unloaded.
     */
    fun ensureActive() {
        leaseProvider()?.ensureActive()
    }

    /**
     * Creates a scalar tracked-value delegate rooted at [fieldName].
     */
    fun <T> trackedValue(fieldName: String, initialValue: T): MongoTrackedValue<T> {
        return MongoTrackedValue(path(fieldName), initialValue, queue, leaseProvider = leaseProvider)
    }
}

/**
 * Runtime contract for a tracked Mongo document wrapper.
 */
interface MongoTrackedDocument<ID : Any, E> : MongoPersistentValue {
    val id: ID

    /**
     * Materializes a detached storage DTO from the current tracked state.
     */
    fun toEntity(): E
}
