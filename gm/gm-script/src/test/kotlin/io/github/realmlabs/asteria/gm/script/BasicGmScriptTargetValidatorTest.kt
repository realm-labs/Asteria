package io.github.realmlabs.asteria.gm.script

import io.github.realmlabs.asteria.core.EntityKind
import io.github.realmlabs.asteria.core.RoleKey
import io.github.realmlabs.asteria.script.ScriptArtifact
import io.github.realmlabs.asteria.script.ScriptExecutionCommand
import io.github.realmlabs.asteria.script.ScriptTarget
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BasicGmScriptTargetValidatorTest {
    @Test
    fun `rejects duplicate entity ids`() = runBlocking {
        val result = BasicGmScriptTargetValidator().validate(
            request(
                ScriptTarget.Entity(EntityKind("player"), listOf("1001", "1001")),
            ),
        )

        val rejected = assertIs<GmScriptTargetValidationResult.Rejected>(result)
        assertEquals(listOf("duplicate entity ids: 1001"), rejected.reasons)
    }

    @Test
    fun `rejects catalog misses`() = runBlocking {
        val result = BasicGmScriptTargetValidator(catalog = TestCatalog).validate(
            request(
                ScriptTarget.Role(RoleKey("missing-role")),
            ),
        )

        val rejected = assertIs<GmScriptTargetValidationResult.Rejected>(result)
        assertEquals(listOf("role missing-role does not exist"), rejected.reasons)
    }

    private fun request(target: ScriptTarget): GmScriptTargetValidationRequest {
        return GmScriptTargetValidationRequest(
            ScriptExecutionCommand(
                executionId = "exec-1",
                target = target,
                artifact = ScriptArtifact("test", "test", ByteArray(0)),
            ),
        )
    }

    private object TestCatalog : GmScriptTargetCatalog {
        override suspend fun roleExists(role: RoleKey): Boolean {
            return role.value == "known-role"
        }
    }
}
