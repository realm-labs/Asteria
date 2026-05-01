package io.github.mikai233.asteria.protocol.protobuf

import com.google.protobuf.GeneratedMessage
import com.google.protobuf.Parser
import io.github.mikai233.asteria.protobuf.ProtobufMessageRegistry
import kotlin.reflect.KClass

class ProtobufProtocolRegistry(
    mappings: Iterable<ProtoMapping<out GeneratedMessage>> = emptyList(),
) {
    private val messages = ProtobufMessageRegistry<Int>()

    init {
        mappings.forEach(::register)
    }

    fun register(mapping: ProtoMapping<out GeneratedMessage>) {
        messages.register(mapping.id, mapping.messageClass, mapping.parser)
    }

    fun idFor(message: GeneratedMessage): Int = messages.keyFor(message)

    fun idFor(messageClass: Class<out GeneratedMessage>): Int {
        return messages.keyFor(messageClass)
    }

    fun parserFor(id: Int): Parser<out GeneratedMessage> {
        return messages.parserFor(id)
    }

    fun encode(message: GeneratedMessage): ProtoFrame {
        val encoded = messages.encode(message)
        return ProtoFrame(encoded.key, encoded.payload)
    }

    fun decode(frame: ProtoFrame): ClientProtoEnvelope {
        val decoded = messages.decode(frame.id, frame.payload)
        return ClientProtoEnvelope(decoded.key, decoded.message)
    }
}

data class ProtoMapping<M : GeneratedMessage>(
    val id: Int,
    val messageClass: KClass<M>,
    val parser: Parser<out M>,
)
