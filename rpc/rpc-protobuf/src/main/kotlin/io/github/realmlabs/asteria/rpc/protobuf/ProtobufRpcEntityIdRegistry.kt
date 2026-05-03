package io.github.realmlabs.asteria.rpc.protobuf

import com.google.protobuf.GeneratedMessage

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

data class ProtobufRpcEntityId<M : GeneratedMessage>(
    val messageClass: Class<M>,
    val resolve: (M) -> String,
)

class MissingProtobufRpcEntityIdException(message: GeneratedMessage) : IllegalArgumentException(
    "protobuf rpc entity id for ${message::class.qualifiedName} not found",
)
