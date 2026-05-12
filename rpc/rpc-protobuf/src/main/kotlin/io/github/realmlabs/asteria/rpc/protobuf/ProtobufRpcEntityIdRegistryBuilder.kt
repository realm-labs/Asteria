package io.github.realmlabs.asteria.rpc.protobuf

import com.google.protobuf.GeneratedMessage

/**
 * Mutable builder for [ProtobufRpcEntityIdRegistry].
 */
class ProtobufRpcEntityIdRegistryBuilder {
    private val entityIds: MutableList<ProtobufRpcEntityId<out GeneratedMessage>> = mutableListOf()

    inline fun <reified M : GeneratedMessage> entityId(noinline resolve: (M) -> String) {
        entityId(M::class.java, resolve)
    }

    fun <M : GeneratedMessage> entityId(
        messageClass: Class<M>,
        resolve: (M) -> String,
    ) {
        entityIds.add(ProtobufRpcEntityId(messageClass, resolve))
    }

    fun build(): ProtobufRpcEntityIdRegistry {
        return ProtobufRpcEntityIdRegistry(entityIds)
    }
}

/**
 * Builds an RPC entity-id registry from a DSL block.
 */
fun protobufRpcEntityIdRegistry(
    configure: ProtobufRpcEntityIdRegistryBuilder.() -> Unit,
): ProtobufRpcEntityIdRegistry {
    return ProtobufRpcEntityIdRegistryBuilder().apply(configure).build()
}
