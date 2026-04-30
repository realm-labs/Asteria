package io.github.mikai233.asteria.script.pekko

import io.github.mikai233.asteria.script.protobuf.toModel
import io.github.mikai233.asteria.script.protobuf.toProto
import io.github.mikai233.asteria.script.protobuf.ExecuteActorScript as ProtoExecuteActorScript
import io.github.mikai233.asteria.script.protobuf.ExecuteEntityActorScript as ProtoExecuteEntityActorScript
import io.github.mikai233.asteria.script.protobuf.ExecuteNodeScript as ProtoExecuteNodeScript

fun ExecuteNodeScript.toProto(): ProtoExecuteNodeScript {
    val builder = ProtoExecuteNodeScript.newBuilder()
        .setCommand(command.toProto())
    originNodeAddress?.let { builder.originNodeAddress = it }
    return builder.build()
}

fun ProtoExecuteNodeScript.toModel(): ExecuteNodeScript {
    require(hasCommand()) { "node script command is required" }
    return ExecuteNodeScript(
        command = command.toModel(),
        originNodeAddress = if (hasOriginNodeAddress()) originNodeAddress else null,
    )
}

fun ExecuteActorScript.toProto(): ProtoExecuteActorScript {
    val builder = ProtoExecuteActorScript.newBuilder()
        .setExecutionId(executionId)
        .setArtifact(artifact.toProto())
        .setMetadata(metadata.toProto())
    target?.let { builder.target = it.toProto() }
    return builder.build()
}

fun ProtoExecuteActorScript.toModel(): ExecuteActorScript {
    require(hasArtifact()) { "actor script artifact is required" }
    return ExecuteActorScript(
        executionId = executionId,
        artifact = artifact.toModel(),
        target = if (hasTarget()) target.toModel() else null,
        metadata = if (hasMetadata()) metadata.toModel() else io.github.mikai233.asteria.script.ScriptExecutionMetadata(),
    )
}

fun ExecuteEntityActorScript.toProto(): ProtoExecuteEntityActorScript {
    val builder = ProtoExecuteEntityActorScript.newBuilder()
        .setId(id)
        .setExecutionId(executionId)
        .setArtifact(artifact.toProto())
        .setMetadata(metadata.toProto())
    target?.let { builder.target = it.toProto() }
    return builder.build()
}

fun ProtoExecuteEntityActorScript.toModel(): ExecuteEntityActorScript {
    require(hasArtifact()) { "entity actor script artifact is required" }
    return ExecuteEntityActorScript(
        id = id,
        executionId = executionId,
        artifact = artifact.toModel(),
        target = if (hasTarget()) target.toModel() else null,
        metadata = if (hasMetadata()) metadata.toModel() else io.github.mikai233.asteria.script.ScriptExecutionMetadata(),
    )
}
