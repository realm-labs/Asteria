package io.github.realmlabs.asteria.persistence

import kotlin.test.Test
import kotlin.test.assertEquals

class FieldHashScanPlanTest {
    @Test
    fun `detects changed fields and map key removals`() {
        val plan = FieldHashScanPlan(
            listOf(
                ScannedField(FieldPath.of("name"), TestEntity::name, Any?::stableHash),
                ScannedField(
                    path = FieldPath.of("bag"),
                    value = TestEntity::bag,
                    hash = Any?::stableHash,
                    children = { value -> (value as Map<*, *>).toMap() },
                ),
            ),
        )
        val beforeEntity = TestEntity("alice", mapOf("a" to 1, "b" to 2))
        val afterEntity = TestEntity("bob", mapOf("a" to 1, "c" to 3))

        val changes = plan.diff(plan.capture(beforeEntity), plan.capture(afterEntity), afterEntity)

        assertEquals(
            listOf(
                FieldChange.Unset(FieldPath.of("bag").child("b")),
                FieldChange.Set(FieldPath.of("name"), "bob"),
                FieldChange.Set(FieldPath.of("bag").child("c"), 3),
            ),
            changes,
        )
    }

    private data class TestEntity(
        val name: String,
        val bag: Map<String, Int>,
    )
}

private fun Any?.stableHash(): Long = hashCode().toLong()
