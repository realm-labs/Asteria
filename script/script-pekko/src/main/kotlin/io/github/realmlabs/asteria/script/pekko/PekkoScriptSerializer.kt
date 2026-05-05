package io.github.realmlabs.asteria.script.pekko

import io.github.realmlabs.asteria.script.ScriptExecutionCommand
import io.github.realmlabs.asteria.script.ScriptExecutionResult
import io.github.realmlabs.asteria.script.protobuf.toModel
import io.github.realmlabs.asteria.script.protobuf.toProto
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.serialization.SerializerWithStringManifest
import io.github.realmlabs.asteria.script.protobuf.ExecuteActorScript as ProtoExecuteActorScript
import io.github.realmlabs.asteria.script.protobuf.ExecuteEntityActorScript as ProtoExecuteEntityActorScript
import io.github.realmlabs.asteria.script.protobuf.ExecuteNodeScript as ProtoExecuteNodeScript
import io.github.realmlabs.asteria.script.protobuf.ExecuteScriptCommand as ProtoExecuteScriptCommand
import io.github.realmlabs.asteria.script.protobuf.ScriptExecutionResult as ProtoScriptExecutionResult

/**
 * Cluster wire serializer for script runtime messages.
 *
 * The numeric [IDENTIFIER] and string manifests are part of the persistent wire contract for rolling upgrades. Change
 * them only with an explicit migration plan that keeps older nodes able to deserialize in-flight messages.
 */
class PekkoScriptSerializer(
    @Suppress("unused") private val system: ExtendedActorSystem,
) : SerializerWithStringManifest() {
    override fun identifier(): Int = IDENTIFIER

    override fun manifest(o: Any): String {
        return when (o) {
            is ScriptExecutionCommand -> SCRIPT_EXECUTION_COMMAND_MANIFEST
            is ScriptExecutionResult -> SCRIPT_EXECUTION_RESULT_MANIFEST
            is ExecuteNodeScript -> EXECUTE_NODE_SCRIPT_MANIFEST
            is ExecuteActorScript -> EXECUTE_ACTOR_SCRIPT_MANIFEST
            is ExecuteEntityActorScript -> EXECUTE_ENTITY_ACTOR_SCRIPT_MANIFEST
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
            SCRIPT_EXECUTION_COMMAND_MANIFEST -> ProtoExecuteScriptCommand.parseFrom(bytes).toModel()
            SCRIPT_EXECUTION_RESULT_MANIFEST -> ProtoScriptExecutionResult.parseFrom(bytes).toModel()
            EXECUTE_NODE_SCRIPT_MANIFEST -> ProtoExecuteNodeScript.parseFrom(bytes).toModel()
            EXECUTE_ACTOR_SCRIPT_MANIFEST -> ProtoExecuteActorScript.parseFrom(bytes).toModel()
            EXECUTE_ENTITY_ACTOR_SCRIPT_MANIFEST -> ProtoExecuteEntityActorScript.parseFrom(bytes).toModel()
            else -> error("unsupported script message manifest $manifest")
        }
    }

    companion object {
        const val IDENTIFIER: Int = 233_120_001
        const val SCRIPT_EXECUTION_COMMAND_MANIFEST: String = "script-execution-command"
        const val SCRIPT_EXECUTION_RESULT_MANIFEST: String = "script-execution-result"
        const val EXECUTE_NODE_SCRIPT_MANIFEST: String = "execute-node-script"
        const val EXECUTE_ACTOR_SCRIPT_MANIFEST: String = "execute-actor-script"
        const val EXECUTE_ENTITY_ACTOR_SCRIPT_MANIFEST: String = "execute-entity-actor-script"
    }
}
