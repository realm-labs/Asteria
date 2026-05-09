package io.github.realmlabs.asteria.script.control

import io.github.realmlabs.asteria.script.ScriptTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ScriptTargetRequestTest {
    @Test
    fun `converts entity request to script target`() {
        val target = ScriptTargetRequest(type = "entity", kind = "player", ids = listOf("1001", "1002"))
            .toScriptTarget()

        val entity = assertIs<ScriptTarget.Entity>(target)
        assertEquals("player", entity.kind.value)
        assertEquals(listOf("1001", "1002"), entity.ids)
    }

    @Test
    fun `rejects empty node addresses`() {
        assertFailsWith<IllegalArgumentException> {
            ScriptTargetRequest(type = "nodes").toScriptTarget()
        }
    }
}
