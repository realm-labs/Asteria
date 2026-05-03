package io.github.realmlabs.asteria.rpc.protobuf.pekko

import com.google.protobuf.GeneratedMessage
import io.github.realmlabs.asteria.rpc.protobuf.ProtobufRpcProtocol
import io.github.realmlabs.asteria.rpc.protobuf.ProtobufRpcProtocols
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.serialization.SerializerWithStringManifest

/**
 * Pekko serializer for internal protobuf RPC messages.
 *
 * Business code can send generated protobuf messages directly through `tell` or `ask`. The serializer stores only the
 * numeric protobuf RPC message id as the manifest, while the message bytes remain the regular protobuf payload.
 */
class ProtobufRpcMessageSerializer : SerializerWithStringManifest {
    private val protocol: ProtobufRpcProtocol

    constructor(system: ExtendedActorSystem) {
        protocol = ProtobufRpcProtocols.load(system.dynamicAccess().classLoader())
    }

    internal constructor(protocol: ProtobufRpcProtocol) {
        this.protocol = protocol
    }

    override fun identifier(): Int = IDENTIFIER

    override fun manifest(o: Any): String {
        require(o is GeneratedMessage) {
            "protobuf RPC serializer only supports ${GeneratedMessage::class.qualifiedName}, got ${o::class.qualifiedName}"
        }
        return protocol.messages.keyFor(o).toString()
    }

    override fun toBinary(o: Any): ByteArray {
        require(o is GeneratedMessage) {
            "protobuf RPC serializer only supports ${GeneratedMessage::class.qualifiedName}, got ${o::class.qualifiedName}"
        }
        protocol.messages.keyFor(o)
        return o.toByteArray()
    }

    override fun fromBinary(bytes: ByteArray, manifest: String): Any {
        val messageId = manifest.toIntOrNull()
            ?: error("protobuf RPC manifest must be a numeric message id, got $manifest")
        return protocol.messages.decode(messageId, bytes).message
    }

    companion object {
        const val IDENTIFIER: Int = 233_130_001
    }
}
