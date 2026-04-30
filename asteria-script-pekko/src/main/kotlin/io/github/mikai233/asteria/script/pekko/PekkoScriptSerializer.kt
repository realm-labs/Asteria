package io.github.mikai233.asteria.script.pekko

import io.github.mikai233.asteria.script.ScriptExecutionCommand
import io.github.mikai233.asteria.script.ScriptExecutionResult
import io.github.mikai233.asteria.script.protobuf.toModel
import io.github.mikai233.asteria.script.protobuf.toProto
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.serialization.SerializerWithStringManifest
import io.github.mikai233.asteria.script.protobuf.ExecuteActorScript as ProtoExecuteActorScript
import io.github.mikai233.asteria.script.protobuf.ExecuteEntityActorScript as ProtoExecuteEntityActorScript
import io.github.mikai233.asteria.script.protobuf.ExecuteNodeScript as ProtoExecuteNodeScript
import io.github.mikai233.asteria.script.protobuf.ExecuteScriptCommand as ProtoExecuteScriptCommand
import io.github.mikai233.asteria.script.protobuf.ScriptExecutionResult as ProtoScriptExecutionResult

class PekkoScriptSerializer(
    @Suppress("unused") private val system: ExtendedActorSystem,
) : SerializerWithStringManifest() {
    override fun identifier(): Int = Identifier

    override fun manifest(o: Any): String {
        return when (o) {
            is ScriptExecutionCommand -> ScriptExecutionCommandManifest
            is ScriptExecutionResult -> ScriptExecutionResultManifest
            is ExecuteNodeScript -> ExecuteNodeScriptManifest
            is ExecuteActorScript -> ExecuteActorScriptManifest
            is ExecuteEntityActorScript -> ExecuteEntityActorScriptManifest
            else -> error("unsupported script message ${o::class.qualifiedName}")
        }
    }

    override fun toBinary(o: Any): ByteArray {
        return when (o) {
            is ScriptExecutionCommand -> o.toProto().toByteArray()
            is ScriptExecutionResult -> o.toProto().toByteArray()
            is ExecuteNodeScript -> o.toProto().toByteArray()
            is ExecuteActorScript -> o.toProto().toByteArray()
            is ExecuteEntityActorScript -> o.toProto().toByteArray()
            else -> error("unsupported script message ${o::class.qualifiedName}")
        }
    }

    override fun fromBinary(bytes: ByteArray, manifest: String): Any {
        return when (manifest) {
            ScriptExecutionCommandManifest -> ProtoExecuteScriptCommand.parseFrom(bytes).toModel()
            ScriptExecutionResultManifest -> ProtoScriptExecutionResult.parseFrom(bytes).toModel()
            ExecuteNodeScriptManifest -> ProtoExecuteNodeScript.parseFrom(bytes).toModel()
            ExecuteActorScriptManifest -> ProtoExecuteActorScript.parseFrom(bytes).toModel()
            ExecuteEntityActorScriptManifest -> ProtoExecuteEntityActorScript.parseFrom(bytes).toModel()
            else -> error("unsupported script message manifest $manifest")
        }
    }

    companion object {
        const val Identifier: Int = 233_120_001
        const val ScriptExecutionCommandManifest: String = "script-execution-command"
        const val ScriptExecutionResultManifest: String = "script-execution-result"
        const val ExecuteNodeScriptManifest: String = "execute-node-script"
        const val ExecuteActorScriptManifest: String = "execute-actor-script"
        const val ExecuteEntityActorScriptManifest: String = "execute-entity-actor-script"
    }
}
