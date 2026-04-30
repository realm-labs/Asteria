package io.github.mikai233.asteria.protocol.protobuf

import com.google.protobuf.GeneratedMessage
import com.google.protobuf.Parser
import kotlin.reflect.KClass

class ProtobufProtocolRegistry(
    mappings: Iterable<ProtoMapping<out GeneratedMessage>> = emptyList(),
) {
    private val idByType: MutableMap<Class<out GeneratedMessage>, Int> = linkedMapOf()
    private val parserById: MutableMap<Int, Parser<out GeneratedMessage>> = linkedMapOf()

    init {
        mappings.forEach(::register)
    }

    fun register(mapping: ProtoMapping<out GeneratedMessage>) {
        check(mapping.id !in parserById) { "duplicate protobuf id ${mapping.id}" }
        check(mapping.messageClass.java !in idByType) {
            "duplicate protobuf message ${mapping.messageClass.qualifiedName}"
        }
        idByType[mapping.messageClass.java] = mapping.id
        parserById[mapping.id] = mapping.parser
    }

    fun idFor(message: GeneratedMessage): Int = idFor(message.javaClass)

    fun idFor(messageClass: Class<out GeneratedMessage>): Int {
        return requireNotNull(idByType[messageClass]) {
            "protobuf id for ${messageClass.name} not found"
        }
    }

    fun parserFor(id: Int): Parser<out GeneratedMessage> {
        return requireNotNull(parserById[id]) { "protobuf parser for id $id not found" }
    }

    fun encode(message: GeneratedMessage): ProtoFrame {
        return ProtoFrame(idFor(message), message.toByteArray())
    }

    fun decode(frame: ProtoFrame): ClientProtoEnvelope {
        val message = parserFor(frame.id).parseFrom(frame.payload) as GeneratedMessage
        return ClientProtoEnvelope(frame.id, message)
    }
}

data class ProtoMapping<M : GeneratedMessage>(
    val id: Int,
    val messageClass: KClass<M>,
    val parser: Parser<out M>,
)
