package io.github.realmlabs.asteria.rpc.protobuf

import com.google.protobuf.GeneratedMessage
import com.google.protobuf.Parser
import io.github.realmlabs.asteria.protobuf.ProtobufMessageRegistry
import io.github.realmlabs.asteria.protobuf.ProtobufMessageRegistryBuilder
import kotlin.reflect.KClass

class ProtobufRpcProtocol(
    val messages: ProtobufMessageRegistry<Int>,
    val entityIds: ProtobufRpcEntityIdRegistry,
)

fun interface ProtobufRpcProtocolContributor {
    fun contribute(builder: ProtobufRpcProtocolBuilder)
}

class ProtobufRpcProtocolBuilder {
    private val messages = ProtobufMessageRegistryBuilder<Int>()
    private val entityIds = ProtobufRpcEntityIdRegistryBuilder()

    fun include(contributor: ProtobufRpcProtocolContributor) {
        contributor.contribute(this)
    }

    inline fun <reified M : GeneratedMessage> message(
        id: Int,
        parser: Parser<out M>,
    ) {
        message(id, M::class, parser)
    }

    fun <M : GeneratedMessage> message(
        id: Int,
        messageClass: KClass<M>,
        parser: Parser<out M>,
    ) {
        messages.message(id, messageClass, parser)
    }

    inline fun <reified M : GeneratedMessage> entityId(noinline resolve: (M) -> String) {
        entityId(M::class.java, resolve)
    }

    fun <M : GeneratedMessage> entityId(
        messageClass: Class<M>,
        resolve: (M) -> String,
    ) {
        entityIds.entityId(messageClass, resolve)
    }

    fun build(): ProtobufRpcProtocol {
        return ProtobufRpcProtocol(
            messages = messages.build(),
            entityIds = entityIds.build(),
        )
    }
}

fun protobufRpcProtocol(
    configure: ProtobufRpcProtocolBuilder.() -> Unit,
): ProtobufRpcProtocol {
    return ProtobufRpcProtocolBuilder().apply(configure).build()
}
