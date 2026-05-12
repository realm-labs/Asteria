package io.github.realmlabs.asteria.protocol.protobuf

import com.google.protobuf.GeneratedMessage
import com.google.protobuf.Parser
import io.github.realmlabs.asteria.protobuf.ProtobufMessageRegistry
import kotlin.reflect.KClass

/**
 * Registry for protobuf protocol ids, parsers, and allowed directions.
 *
 * Gateway codecs use [decode] for client-to-server frames and [encode] for server-to-client messages. Both operations
 * validate [ProtoDirection] so server-only messages cannot enter gateway routing and client-only messages cannot be
 * sent to a client by mistake.
 */
class ProtobufProtocolRegistry(
    mappings: Iterable<ProtoMapping<out GeneratedMessage>> = emptyList(),
) {
    private val messages = ProtobufMessageRegistry<Int>()
    private val directionById: MutableMap<Int, ProtoDirection> = linkedMapOf()

    init {
        mappings.forEach(::register)
    }

    fun register(mapping: ProtoMapping<out GeneratedMessage>) {
        messages.register(mapping.id, mapping.messageClass, mapping.parser)
        directionById[mapping.id] = mapping.direction
    }

    fun idFor(message: GeneratedMessage): Int = messages.keyFor(message)

    fun idFor(messageClass: Class<out GeneratedMessage>): Int {
        return messages.keyFor(messageClass)
    }

    fun parserFor(id: Int): Parser<out GeneratedMessage> {
        return messages.parserFor(id)
    }

    fun directionFor(id: Int): ProtoDirection {
        messages.typeFor(id)
        return requireNotNull(directionById[id]) { "protobuf direction for id $id not found" }
    }

    fun directionFor(messageClass: Class<out GeneratedMessage>): ProtoDirection {
        return directionFor(idFor(messageClass))
    }

    fun encode(message: GeneratedMessage): ProtoFrame {
        val id = idFor(message)
        val direction = directionFor(id)
        require(direction.allowsServerToClient) {
            "protobuf message ${message.javaClass.name} with id $id is not allowed in server-to-client direction"
        }
        val encoded = messages.encode(message)
        return ProtoFrame(encoded.key, encoded.payload)
    }

    fun decode(frame: ProtoFrame): ClientProtoEnvelope {
        val direction = directionFor(frame.id)
        require(direction.allowsClientToServer) {
            "protobuf message id ${frame.id} is not allowed in client-to-server direction"
        }
        val decoded = messages.decode(frame.id, frame.payload)
        return ClientProtoEnvelope(decoded.key, decoded.message)
    }
}

/**
 * Allowed wire direction for one protobuf message id.
 */
enum class ProtoDirection(
    val allowsClientToServer: Boolean,
    val allowsServerToClient: Boolean,
) {
    CLIENT_TO_SERVER(allowsClientToServer = true, allowsServerToClient = false),
    SERVER_TO_CLIENT(allowsClientToServer = false, allowsServerToClient = true),
    BIDIRECTIONAL(allowsClientToServer = true, allowsServerToClient = true),
}

/**
 * Static mapping between a protobuf message class, its parser, wire id, and direction.
 */
data class ProtoMapping<M : GeneratedMessage>(
    val id: Int,
    val direction: ProtoDirection,
    val messageClass: KClass<M>,
    val parser: Parser<out M>,
)
