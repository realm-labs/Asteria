package io.github.realmlabs.asteria.persistence.mongodb

import org.bson.Document

/**
 * Value that can provide its Mongo-safe persistent representation.
 */
interface MongoPersistentValue {
    /**
     * Converts this value into the shape written to Mongo or into a parent Mongo document.
     */
    fun toMongoValue(): Any?
}

/**
 * Normalizes Kotlin values into Mongo-safe values for dirty writes and hashing.
 *
 * Maps become [Document] with encoded keys, enums become names, primitive arrays become lists, and tracked values
 * delegate to [MongoPersistentValue.toMongoValue].
 */
fun mongoValueOf(value: Any?): Any? {
    return when (value) {
        is MongoPersistentValue -> value.toMongoValue()
        is Enum<*> -> value.name
        is IntArray -> value.toList()
        is LongArray -> value.toList()
        is BooleanArray -> value.toList()
        is DoubleArray -> value.toList()
        is FloatArray -> value.toList()
        is Map<*, *> -> Document(
            value.entries.associate { (key, childValue) ->
                MongoPath.encodePathPart(key) to mongoValueOf(childValue)
            },
        )

        is List<*> -> value.map(::mongoValueOf)
        is Set<*> -> value.map(::mongoValueOf)
        is Collection<*> -> value.map(::mongoValueOf)
        else -> value
    }
}
