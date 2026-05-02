package io.github.mikai233.asteria.persistence.mongodb

import org.bson.Document

/**
 * Value that can provide its Mongo-safe persistent representation.
 */
interface MongoPersistentValue {
    fun toMongoValue(): Any?
}

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
