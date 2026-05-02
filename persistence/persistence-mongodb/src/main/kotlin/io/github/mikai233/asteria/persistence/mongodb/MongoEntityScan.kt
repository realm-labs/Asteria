package io.github.mikai233.asteria.persistence.mongodb

import io.github.mikai233.asteria.persistence.EntityScanPlan
import io.github.mikai233.asteria.persistence.FieldChange
import io.github.mikai233.asteria.persistence.FieldHashScanPlan
import io.github.mikai233.asteria.persistence.FieldPath
import io.github.mikai233.asteria.persistence.ScannedField
import org.bson.Document
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Creates a Mongo-oriented hash scan plan.
 */
fun <E : Any> mongoScanPlan(vararg fields: ScannedField<E>): EntityScanPlan<E> {
    return FieldHashScanPlan(fields.asIterable())
}

/**
 * Tracks one top-level field as a whole value.
 */
fun <E : Any> mongoScannedField(
    fieldName: String,
    value: (E) -> Any?,
): ScannedField<E> {
    return ScannedField(FieldPath.of(fieldName), value, MongoStableHasher::hash)
}

/**
 * Tracks one map-like field by individual keys.
 */
fun <E : Any> mongoScannedMapField(
    fieldName: String,
    value: (E) -> Map<*, *>?,
): ScannedField<E> {
    return ScannedField(
        path = FieldPath.of(fieldName),
        value = value,
        hash = MongoStableHasher::hash,
        children = { mapValue ->
            (mapValue as? Map<*, *>)
                .orEmpty()
                .entries
                .associate { (key, childValue) -> key to childValue }
        },
    )
}

/**
 * Converts database-agnostic scan changes into Mongo update operations.
 */
object MongoFieldChangeEncoder {
    fun encode(collectionName: String, documentId: Any?, change: FieldChange): MongoChangeOp {
        val path = MongoPath(collectionName, documentId, change.path.toMongoFieldPath())
        return when (change) {
            is FieldChange.Set -> MongoChangeOp.Set(path, mongoValueOf(change.value))
            is FieldChange.Unset -> MongoChangeOp.Unset(path)
        }
    }
}

fun FieldPath.toMongoFieldPath(): String {
    return parts.joinToString(".") { part -> MongoPath.encodePathPart(part) }
}

/**
 * Stable, storage-oriented hasher used by Mongo scan dirty tracking.
 *
 * The hasher normalizes values through [mongoValueOf] where possible, sorts map/document keys, preserves list order, and
 * sorts set element hashes. It is intentionally independent from object identity, so equal persistent values produce the
 * same hash across scans.
 */
object MongoStableHasher {
    fun hash(value: Any?): Long {
        val state = HashState()
        state.writeValue(value)
        return state.value
    }
}

private class HashState {
    var value: Long = FNV_OFFSET
        private set

    fun writeValue(value: Any?) {
        when (value) {
            null -> writeMarker("null")
            is MongoPersistentValue -> writeValue(value.toMongoValue())
            is Enum<*> -> writeScalar("enum", value.name)
            is String -> writeScalar("string", value)
            is Boolean -> writeScalar("boolean", value.toString())
            is Byte,
            is Short,
            is Int,
            is Long,
                -> writeScalar("integer", value.toString())

            is Float,
            is Double,
                -> writeScalar("decimal", value.toString())

            is BigDecimal -> writeScalar("big_decimal", value.stripTrailingZeros().toPlainString())
            is BigInteger -> writeScalar("big_integer", value.toString())
            is ByteArray -> writeBytes("byte_array", value)
            is IntArray -> writeOrdered("int_array", value.asIterable())
            is LongArray -> writeOrdered("long_array", value.asIterable())
            is BooleanArray -> writeOrdered("boolean_array", value.asIterable())
            is FloatArray -> writeOrdered("float_array", value.asIterable())
            is DoubleArray -> writeOrdered("double_array", value.asIterable())
            is Document -> writeDocument(value)
            is Map<*, *> -> writeMap(value)
            is Set<*> -> writeSet(value)
            is Iterable<*> -> writeOrdered("iterable", value)
            else -> writeValue(mongoValueOf(value).takeUnless { it === value } ?: value.hashCode())
        }
    }

    private fun writeDocument(value: Document) {
        writeMarker("document")
        value.entries.sortedBy { it.key }.forEach { (key, childValue) ->
            writeScalar("key", key)
            writeValue(childValue)
        }
    }

    private fun writeMap(value: Map<*, *>) {
        writeMarker("map")
        value.entries.sortedBy { (key, _) -> MongoPath.encodePathPart(key) }.forEach { (key, childValue) ->
            writeScalar("key", MongoPath.encodePathPart(key))
            writeValue(childValue)
        }
    }

    private fun writeSet(value: Set<*>) {
        writeMarker("set")
        value.map(MongoStableHasher::hash).sorted().forEach { childHash ->
            writeScalar("hash", childHash.toString())
        }
    }

    private fun writeOrdered(marker: String, value: Iterable<*>) {
        writeMarker(marker)
        value.forEach(::writeValue)
    }

    private fun writeScalar(type: String, value: String) {
        writeMarker(type)
        writeString(value)
    }

    private fun writeBytes(type: String, bytes: ByteArray) {
        writeMarker(type)
        bytes.forEach { byte -> mix(byte.toInt()) }
    }

    private fun writeMarker(value: String) {
        writeString("#$value:")
    }

    private fun writeString(value: String) {
        value.encodeToByteArray().forEach { byte -> mix(byte.toInt()) }
    }

    private fun mix(byte: Int) {
        value = value xor (byte and BYTE_MASK).toLong()
        value *= FNV_PRIME
    }

    private companion object {
        const val BYTE_MASK = 0xff
        const val FNV_OFFSET = -3750763034362895579L
        const val FNV_PRIME = 1099511628211L
    }
}
