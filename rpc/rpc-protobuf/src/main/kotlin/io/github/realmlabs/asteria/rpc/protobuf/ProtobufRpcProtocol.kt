package io.github.realmlabs.asteria.rpc.protobuf

import com.google.protobuf.GeneratedMessage
import com.google.protobuf.Parser
import io.github.realmlabs.asteria.protobuf.ProtobufMessageRegistry
import io.github.realmlabs.asteria.protobuf.ProtobufMessageRegistryBuilder
import kotlin.reflect.KClass

class ProtobufRpcProtocol(
    val messages: ProtobufMessageRegistry<Int>,
)

fun interface ProtobufRpcProtocolContributor {
    fun contribute(builder: ProtobufRpcProtocolBuilder)
}

class ProtobufRpcProtocolBuilder {
    private val messages = ProtobufMessageRegistryBuilder<Int>()

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

    fun build(): ProtobufRpcProtocol {
        return ProtobufRpcProtocol(
            messages = messages.build(),
        )
    }
}

fun protobufRpcProtocol(
    configure: ProtobufRpcProtocolBuilder.() -> Unit,
): ProtobufRpcProtocol {
    return ProtobufRpcProtocolBuilder().apply(configure).build()
}
