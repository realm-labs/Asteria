package io.github.realmlabs.asteria.rpc.protobuf

import com.google.protobuf.GeneratedMessage

/**
 * Registry of entity-id extractors for protobuf RPC messages.
 *
 * Missing mappings are valid for calls that do not require sharding; use [requireEntityId] at routing boundaries that
 * must have an entity id.
 */
class ProtobufRpcEntityIdRegistry(
    entityIds: Iterable<ProtobufRpcEntityId<out GeneratedMessage>> = emptyList(),
) {
    private val entityIdsByType: Map<Class<out GeneratedMessage>, ProtobufRpcEntityId<out GeneratedMessage>> =
        buildMap {
            entityIds.forEach { entityId ->
                check(entityId.messageClass !in this) {
                    "duplicate protobuf rpc entity id for ${entityId.messageClass.name}"
                }
                put(entityId.messageClass, entityId)
            }
        }

    fun entityId(message: GeneratedMessage): String? {
        val entityId = entityIdsByType[message.javaClass] ?: return null
        return entityIdUnchecked(entityId, message)
    }

    fun requireEntityId(message: GeneratedMessage): String {
        return entityId(message) ?: throw MissingProtobufRpcEntityIdException(message)
    }

    @Suppress("UNCHECKED_CAST")
    private fun entityIdUnchecked(
        entityId: ProtobufRpcEntityId<out GeneratedMessage>,
        message: GeneratedMessage,
    ): String {
        return (entityId as ProtobufRpcEntityId<GeneratedMessage>).resolve(message)
    }
}

/**
 * Entity-id extractor for one protobuf RPC message class.
 */
data class ProtobufRpcEntityId<M : GeneratedMessage>(
    val messageClass: Class<M>,
    val resolve: (M) -> String,
)

/**
 * Raised when a routing path requires an entity id but no extractor was registered for the message type.
 */
class MissingProtobufRpcEntityIdException(message: GeneratedMessage) : IllegalArgumentException(
    "protobuf rpc entity id for ${message::class.qualifiedName} not found",
)
