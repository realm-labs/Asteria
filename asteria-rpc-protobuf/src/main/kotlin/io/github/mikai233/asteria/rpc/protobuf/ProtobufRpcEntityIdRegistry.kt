package io.github.mikai233.asteria.rpc.protobuf

import com.google.protobuf.GeneratedMessage
import io.github.mikai233.asteria.rpc.RpcEntityIdRegistry

class ProtobufRpcEntityIdRegistry(
    entityIds: Iterable<ProtobufRpcEntityId<out GeneratedMessage>> = emptyList(),
) : RpcEntityIdRegistry {
    private val entityIdsByType: Map<Class<out GeneratedMessage>, ProtobufRpcEntityId<out GeneratedMessage>> = buildMap {
        entityIds.forEach { entityId ->
            check(entityId.messageClass !in this) {
                "duplicate protobuf rpc entity id for ${entityId.messageClass.name}"
            }
            put(entityId.messageClass, entityId)
        }
    }

    override fun entityId(message: Any): String? {
        if (message !is GeneratedMessage) {
            return null
        }
        val entityId = entityIdsByType[message.javaClass] ?: return null
        return entityIdUnchecked(entityId, message)
    }

    private fun entityIdUnchecked(
        entityId: ProtobufRpcEntityId<out GeneratedMessage>,
        message: GeneratedMessage,
    ): String {
        @Suppress("UNCHECKED_CAST")
        return (entityId as ProtobufRpcEntityId<GeneratedMessage>).resolve(message)
    }
}

data class ProtobufRpcEntityId<M : GeneratedMessage>(
    val messageClass: Class<M>,
    val resolve: (M) -> String,
)
