package io.github.mikai233.asteria.protobuf

import com.google.protobuf.GeneratedMessage
import com.google.protobuf.Parser
import kotlin.reflect.KClass

/**
 * Registry for protobuf messages keyed by an application-defined id.
 *
 * The key type is deliberately generic. Client protocols can use numeric ids,
 * broadcast can use stable type names, and RPC-related metadata can reuse the
 * same registered message class/parsers without sharing one global id space.
 */
class ProtobufMessageRegistry<K : Any>(
    mappings: Iterable<ProtobufMessageMapping<K, out GeneratedMessage>> = emptyList(),
) {
    private val keyByType: MutableMap<Class<out GeneratedMessage>, K> = linkedMapOf()
    private val parserByKey: MutableMap<K, Parser<out GeneratedMessage>> = linkedMapOf()
    private val typeByKey: MutableMap<K, KClass<out GeneratedMessage>> = linkedMapOf()

    init {
        mappings.forEach(::register)
    }

    fun register(mapping: ProtobufMessageMapping<K, out GeneratedMessage>) {
        register(mapping.key, mapping.messageClass, mapping.parser)
    }

    fun register(
        key: K,
        messageClass: KClass<out GeneratedMessage>,
        parser: Parser<out GeneratedMessage>,
    ) {
        check(key !in parserByKey) { "duplicate protobuf key $key" }
        check(messageClass.java !in keyByType) {
            "duplicate protobuf message ${messageClass.qualifiedName}"
        }
        keyByType[messageClass.java] = key
        parserByKey[key] = parser
        typeByKey[key] = messageClass
    }

    fun keyFor(message: GeneratedMessage): K = keyFor(message.javaClass)

    fun keyFor(messageClass: Class<out GeneratedMessage>): K {
        return requireNotNull(keyByType[messageClass]) {
            "protobuf key for ${messageClass.name} not found"
        }
    }

    fun typeFor(key: K): KClass<out GeneratedMessage> {
        return requireNotNull(typeByKey[key]) { "protobuf message type for key $key not found" }
    }

    fun parserFor(key: K): Parser<out GeneratedMessage> {
        return requireNotNull(parserByKey[key]) { "protobuf parser for key $key not found" }
    }

    fun encode(message: GeneratedMessage): ProtobufEncodedMessage<K> {
        return ProtobufEncodedMessage(keyFor(message), message.toByteArray())
    }

    fun decode(encoded: ProtobufEncodedMessage<K>): ProtobufDecodedMessage<K> {
        return decode(encoded.key, encoded.payload)
    }

    fun decode(key: K, payload: ByteArray): ProtobufDecodedMessage<K> {
        val message = parserFor(key).parseFrom(payload) as GeneratedMessage
        return ProtobufDecodedMessage(key, message)
    }
}

data class ProtobufMessageMapping<K : Any, M : GeneratedMessage>(
    val key: K,
    val messageClass: KClass<M>,
    val parser: Parser<out M>,
)

/**
 * Serialized protobuf message with its registry key.
 */
data class ProtobufEncodedMessage<K : Any>(
    val key: K,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ProtobufEncodedMessage<*>

        if (key != other.key) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

data class ProtobufDecodedMessage<K : Any>(
    val key: K,
    val message: GeneratedMessage,
)

class ProtobufMessageRegistryBuilder<K : Any> {
    private val mappings: MutableList<ProtobufMessageMapping<K, out GeneratedMessage>> = mutableListOf()

    inline fun <reified M : GeneratedMessage> message(
        key: K,
        parser: Parser<out M>,
    ) {
        message(key, M::class, parser)
    }

    fun <M : GeneratedMessage> message(
        key: K,
        messageClass: KClass<M>,
        parser: Parser<out M>,
    ) {
        mappings.add(ProtobufMessageMapping(key, messageClass, parser))
    }

    fun build(): ProtobufMessageRegistry<K> {
        return ProtobufMessageRegistry(mappings)
    }
}

fun <K : Any> protobufMessageRegistry(
    configure: ProtobufMessageRegistryBuilder<K>.() -> Unit,
): ProtobufMessageRegistry<K> {
    return ProtobufMessageRegistryBuilder<K>().apply(configure).build()
}
