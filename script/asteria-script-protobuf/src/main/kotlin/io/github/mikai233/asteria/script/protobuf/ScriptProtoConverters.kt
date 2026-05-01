package io.github.mikai233.asteria.script.protobuf

import com.google.protobuf.ByteString
import io.github.mikai233.asteria.core.EntityKind
import io.github.mikai233.asteria.core.RoleKey
import io.github.mikai233.asteria.core.SingletonName
import io.github.mikai233.asteria.script.ScriptExecutionCommand as ModelScriptExecutionCommand
import io.github.mikai233.asteria.script.ScriptExecutionMetadata as ModelScriptExecutionMetadata
import io.github.mikai233.asteria.script.ScriptExecutionResult as ModelScriptExecutionResult
import io.github.mikai233.asteria.script.ScriptTarget as ModelScriptTarget
import io.github.mikai233.asteria.script.ScriptArtifact as ModelScriptArtifact

fun ModelScriptArtifact.toProto(): ScriptArtifact {
    val builder = ScriptArtifact.newBuilder()
        .setName(name)
        .setEngine(engine)
        .setBody(ByteString.copyFrom(body))
    extra?.let { builder.extra = ByteString.copyFrom(it) }
    checksum?.let { builder.checksum = it }
    return builder.build()
}

fun ScriptArtifact.toModel(): ModelScriptArtifact {
    return ModelScriptArtifact(
        name = name,
        engine = engine,
        body = body.toByteArray(),
        extra = if (hasExtra()) extra.toByteArray() else null,
        checksum = if (hasChecksum()) checksum else null,
    )
}

fun ModelScriptTarget.toProto(): ScriptTarget {
    val builder = ScriptTarget.newBuilder()
    when (this) {
        ModelScriptTarget.AllNodes -> builder.setAllNodes(AllNodesTarget.getDefaultInstance())
        is ModelScriptTarget.ActorPath -> builder.setActorPath(ActorPathTarget.newBuilder().setPath(path))
        is ModelScriptTarget.Entity -> builder.setEntity(EntityTarget.newBuilder().setKind(kind.value).setId(id))
        is ModelScriptTarget.Node -> builder.setNode(NodeTarget.newBuilder().setAddress(address))
        is ModelScriptTarget.Role -> builder.setRole(RoleTarget.newBuilder().setRole(role.value))
        is ModelScriptTarget.Singleton -> builder.setSingleton(SingletonTarget.newBuilder().setName(name.value))
    }
    return builder.build()
}

fun ScriptTarget.toModel(): ModelScriptTarget {
    return when (targetCase) {
        ScriptTarget.TargetCase.ALL_NODES -> ModelScriptTarget.AllNodes
        ScriptTarget.TargetCase.ACTOR_PATH -> ModelScriptTarget.ActorPath(actorPath.path)
        ScriptTarget.TargetCase.ENTITY -> ModelScriptTarget.Entity(EntityKind(entity.kind), entity.id)
        ScriptTarget.TargetCase.NODE -> ModelScriptTarget.Node(node.address)
        ScriptTarget.TargetCase.ROLE -> ModelScriptTarget.Role(RoleKey(role.role))
        ScriptTarget.TargetCase.SINGLETON -> ModelScriptTarget.Singleton(SingletonName(singleton.name))
        ScriptTarget.TargetCase.TARGET_NOT_SET,
        null,
        -> error("script target is not set")
    }
}

fun ModelScriptExecutionMetadata.toProto(): ScriptExecutionMetadata {
    val builder = ScriptExecutionMetadata.newBuilder()
    requester?.let { builder.requester = it }
    reason?.let { builder.reason = it }
    builder.putAllAttributes(attributes)
    return builder.build()
}

fun ScriptExecutionMetadata.toModel(): ModelScriptExecutionMetadata {
    return ModelScriptExecutionMetadata(
        requester = if (hasRequester()) requester else null,
        reason = if (hasReason()) reason else null,
        attributes = attributesMap,
    )
}

fun ModelScriptExecutionCommand.toProto(): ExecuteScriptCommand {
    return ExecuteScriptCommand.newBuilder()
        .setExecutionId(executionId)
        .setTarget(target.toProto())
        .setArtifact(artifact.toProto())
        .setMetadata(metadata.toProto())
        .build()
}

fun ExecuteScriptCommand.toModel(): ModelScriptExecutionCommand {
    require(hasTarget()) { "script command target is required" }
    require(hasArtifact()) { "script command artifact is required" }
    return ModelScriptExecutionCommand(
        executionId = executionId,
        target = target.toModel(),
        artifact = artifact.toModel(),
        metadata = if (hasMetadata()) metadata.toModel() else ModelScriptExecutionMetadata(),
    )
}

fun ModelScriptExecutionResult.toProto(): ScriptExecutionResult {
    val builder = ScriptExecutionResult.newBuilder()
        .setExecutionId(executionId)
        .setSuccess(success)
    target?.let { builder.target = it }
    error?.let { builder.error = it }
    nodeAddress?.let { builder.nodeAddress = it }
    actorPath?.let { builder.actorPath = it }
    return builder.build()
}

fun ScriptExecutionResult.toModel(): ModelScriptExecutionResult {
    return ModelScriptExecutionResult(
        executionId = executionId,
        success = success,
        target = if (hasTarget()) target else null,
        error = if (hasError()) error else null,
        nodeAddress = if (hasNodeAddress()) nodeAddress else null,
        actorPath = if (hasActorPath()) actorPath else null,
    )
}
