package io.github.realmlabs.asteria.gm.script.spring

import io.github.realmlabs.asteria.script.ScriptTarget
import io.github.realmlabs.asteria.script.control.ScriptTargetRequest
import io.github.realmlabs.asteria.script.job.ScriptJobExecutionAttributes
import java.util.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GmScriptHttpModelsTest {
    @Test
    fun `converts submit request to internal script command`() {
        val body = "println(\"hello\")".encodeToByteArray()
        val request = GmScriptSubmitRequest(
            executionId = "exec-1",
            target = ScriptTargetRequest(type = "entity", kind = "player", ids = listOf("1001")),
            artifact = GmScriptArtifactRequest(
                name = "repair-player",
                engine = "groovy",
                bodyBase64 = Base64.getEncoder().encodeToString(body),
            ),
            metadata = GmScriptMetadataRequest(
                reason = "repair data",
                attributes = mapOf("ticket" to "T-1"),
            ),
        )

        val command = request.toCommand("alice")

        assertEquals("exec-1", command.executionId)
        val target = assertIs<ScriptTarget.Entity>(command.target)
        assertEquals("player", target.kind.value)
        assertEquals(listOf("1001"), target.ids)
        assertEquals("repair-player", command.artifact.name)
        assertEquals("groovy", command.artifact.engine)
        assertContentEquals(body, command.artifact.body)
        assertEquals("alice", command.metadata.requester)
        assertEquals("repair data", command.metadata.reason)
        assertEquals("T-1", command.metadata.attributes["ticket"])
    }

    @Test
    fun `converts multi entity target to script target with multiple ids`() {
        val request = GmScriptSubmitRequest(
            executionId = "exec-2",
            target = ScriptTargetRequest(type = "entity", kind = "player", ids = listOf("1001", "1002")),
            artifact = GmScriptArtifactRequest(
                name = "compensate-player",
                engine = "groovy",
                bodyBase64 = Base64.getEncoder().encodeToString("ok".encodeToByteArray()),
            ),
        )

        val command = request.toCommand("alice")

        val target = assertIs<ScriptTarget.Entity>(command.target)
        assertEquals("player", target.kind.value)
        assertEquals(listOf("1001", "1002"), target.ids)
    }

    @Test
    fun `converts GM execution options to script metadata`() {
        val request = GmScriptSubmitRequest(
            executionId = "exec-3",
            target = ScriptTargetRequest(type = "entity", kind = "player", ids = listOf("1001")),
            artifact = GmScriptArtifactRequest(
                name = "compensate-player",
                engine = "groovy",
                bodyBase64 = Base64.getEncoder().encodeToString("ok".encodeToByteArray()),
            ),
            options = GmScriptExecutionOptionsRequest(maxConcurrentItems = 64),
        )

        val command = request.toCommand("alice")

        assertEquals("64", command.metadata.attributes[ScriptJobExecutionAttributes.MAX_CONCURRENT_ITEMS])
    }
}
