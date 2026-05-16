package io.github.realmlabs.asteria.persistence.mongodb.common

import org.bson.BsonDocument
import org.bson.BsonDocumentReader
import org.bson.BsonValue
import org.bson.codecs.Codec
import org.bson.codecs.DecoderContext
import org.bson.codecs.configuration.CodecConfigurationException
import org.bson.codecs.configuration.CodecRegistry
import kotlin.reflect.KClass

internal class MongoProjectedIdDecoder<ID : Any>(
    private val collectionName: String,
    private val idType: KClass<ID>,
    codecRegistry: CodecRegistry,
) {
    private val codec: Codec<ID> = codecRegistry.get(idType.javaObjectType)
    private val decoderContext: DecoderContext = DecoderContext.builder().build()

    fun decode(projectedDocument: BsonDocument): ID {
        val value = projectedDocument["_id"]
            ?: throw CodecConfigurationException("Projected Mongo document from $collectionName is missing _id.")
        return decode(value)
    }

    private fun decode(value: BsonValue): ID {
        val reader = BsonDocumentReader(BsonDocument("_id", value))
        return try {
            reader.readStartDocument()
            reader.readName("_id")
            val id = codec.decode(reader, decoderContext)
            reader.readEndDocument()
            id
        } catch (e: Exception) {
            throw CodecConfigurationException(
                "Unable to decode projected _id from $collectionName as ${idType.qualifiedName}.",
                e,
            )
        }
    }
}
