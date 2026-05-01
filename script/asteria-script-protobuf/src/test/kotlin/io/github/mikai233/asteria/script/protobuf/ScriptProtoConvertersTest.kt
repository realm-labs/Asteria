package io.github.mikai233.asteria.script.protobuf

import io.github.mikai233.asteria.core.EntityKind
import io.github.mikai233.asteria.script.ScriptArtifact
import io.github.mikai233.asteria.script.ScriptExecutionCommand
import io.github.mikai233.asteria.script.ScriptExecutionMetadata
import io.github.mikai233.asteria.script.ScriptExecutionResult
import io.github.mikai233.asteria.script.ScriptTarget
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ScriptProtoConvertersTest {
    @Test
    fun commandRoundTripsThroughProto() {
        val command = ScriptExecutionCommand(
            executionId = "script-1",
            target = ScriptTarget.Entity(EntityKind("player"), "10001"),
            artifact = ScriptArtifact(
                name = "fix-player-state",
                engine = "jar",
                body = byteArrayOf(1, 2, 3),
                extra = byteArrayOf(4, 5),
                checksum = "sha256:test",
            ),
            metadata = ScriptExecutionMetadata(
                requester = "ops:mikai",
                reason = "fix stuck state",
                attributes = mapOf("ticket" to "INC-10001"),
            ),
        )

        val decoded = command.toProto().toModel()

        assertEquals(command.executionId, decoded.executionId)
        assertEquals(command.target, decoded.target)
        assertEquals(command.artifact.name, decoded.artifact.name)
        assertEquals(command.artifact.engine, decoded.artifact.engine)
        assertContentEquals(command.artifact.body, decoded.artifact.body)
        assertContentEquals(command.artifact.extra, decoded.artifact.extra)
        assertEquals(command.artifact.checksum, decoded.artifact.checksum)
        assertEquals(command.metadata, decoded.metadata)
    }

    @Test
    fun resultRoundTripsThroughProto() {
        val result = ScriptExecutionResult(
            executionId = "script-2",
            success = false,
            target = "player-10001",
            error = "denied",
            nodeAddress = "pekko://game@127.0.0.1:25520",
            actorPath = "pekko://game/user/player",
        )

        assertEquals(result, result.toProto().toModel())
    }
}
