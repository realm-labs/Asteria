package io.github.realmlabs.asteria.rpc.protobuf

import com.google.protobuf.GeneratedMessage
import com.google.protobuf.Parser
import io.github.realmlabs.asteria.protobuf.ProtobufMessageRegistry
import io.github.realmlabs.asteria.protobuf.ProtobufMessageRegistryBuilder
import io.github.realmlabs.asteria.rpc.*
import kotlin.reflect.KClass

class ProtobufRpcProtocol(
    val messages: ProtobufMessageRegistry<Int>,
    val protocol: RpcProtocol,
) {
    val methods = protocol.methods
    val entityIds = protocol.entityIds
}

fun interface ProtobufRpcProtocolContributor {
    fun contribute(builder: ProtobufRpcProtocolBuilder)
}

class ProtobufRpcProtocolBuilder {
    private val messages = ProtobufMessageRegistryBuilder<Int>()
    private val methods: MutableList<RpcMethod<*, *>> = mutableListOf()
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

    inline fun <reified Req : GeneratedMessage, reified Resp : GeneratedMessage> entityCall(
        id: Int,
        name: String,
        target: RpcTarget.Entity,
        requestParser: Parser<out Req>,
        responseId: Int,
        responseParser: Parser<out Resp>,
        noinline entityIdResolver: (Req) -> String,
    ) {
        call(
            id = id,
            name = name,
            requestClass = Req::class,
            requestParser = requestParser,
            responseId = responseId,
            responseClass = Resp::class,
            responseParser = responseParser,
            target = target,
            entityIdResolver = entityIdResolver,
        )
    }

    inline fun <reified Req : GeneratedMessage, reified Resp : GeneratedMessage> call(
        id: Int,
        name: String,
        target: RpcTarget,
        requestParser: Parser<out Req>,
        responseId: Int,
        responseParser: Parser<out Resp>,
        noinline entityIdResolver: ((Req) -> String)? = null,
    ) {
        call(
            id = id,
            name = name,
            requestClass = Req::class,
            requestParser = requestParser,
            responseId = responseId,
            responseClass = Resp::class,
            responseParser = responseParser,
            target = target,
            entityIdResolver = entityIdResolver,
        )
    }

    fun <Req : GeneratedMessage, Resp : GeneratedMessage> call(
        id: Int,
        name: String,
        requestClass: KClass<Req>,
        requestParser: Parser<out Req>,
        responseId: Int,
        responseClass: KClass<Resp>,
        responseParser: Parser<out Resp>,
        target: RpcTarget,
        entityIdResolver: ((Req) -> String)? = null,
    ) {
        message(id, requestClass, requestParser)
        message(responseId, responseClass, responseParser)
        methods += RpcMethod<Req, Resp>(
            id = id,
            name = name,
            requestType = requestClass,
            responseType = responseClass,
            target = target,
            mode = RpcMode.ASK,
            entityIdResolver = entityIdResolver,
        )
        entityIdResolver?.let { entityId(requestClass.java, it) }
    }

    inline fun <reified Req : GeneratedMessage> tell(
        id: Int,
        name: String,
        target: RpcTarget,
        requestParser: Parser<out Req>,
        noinline entityIdResolver: ((Req) -> String)? = null,
    ) {
        tell(id, name, Req::class, requestParser, target, entityIdResolver)
    }

    fun <Req : GeneratedMessage> tell(
        id: Int,
        name: String,
        requestClass: KClass<Req>,
        requestParser: Parser<out Req>,
        target: RpcTarget,
        entityIdResolver: ((Req) -> String)? = null,
    ) {
        message(id, requestClass, requestParser)
        methods += RpcMethod<Req, GeneratedMessage>(
            id = id,
            name = name,
            requestType = requestClass,
            responseType = null,
            target = target,
            mode = RpcMode.TELL,
            entityIdResolver = entityIdResolver,
        )
        entityIdResolver?.let { entityId(requestClass.java, it) }
    }

    fun build(): ProtobufRpcProtocol {
        val methodRegistry = StaticRpcMethodRegistry(methods)
        val entityIdRegistry = entityIds.build()
        return ProtobufRpcProtocol(
            messages = messages.build(),
            protocol = RpcProtocol(
                methods = methodRegistry,
                entityIds = entityIdRegistry,
            ),
        )
    }
}

fun protobufRpcProtocol(
    configure: ProtobufRpcProtocolBuilder.() -> Unit,
): ProtobufRpcProtocol {
    return ProtobufRpcProtocolBuilder().apply(configure).build()
}
