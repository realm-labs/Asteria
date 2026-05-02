package io.github.mikai233.asteria.persistence.mongodb

import io.github.mikai233.asteria.persistence.FieldChange
import io.github.mikai233.asteria.persistence.FieldPath
import org.bson.Document
import kotlin.test.Test
import kotlin.test.assertEquals

class MongoEntityScanTest {
    @Test
    fun `encodes scan changes into mongo update paths`() {
        val set = MongoFieldChangeEncoder.encode(
            collectionName = "players",
            documentId = 1001L,
            change = FieldChange.Set(FieldPath.of("bag").child("a.b"), mapOf("count" to 2)),
        )
        val unset = MongoFieldChangeEncoder.encode(
            collectionName = "players",
            documentId = 1001L,
            change = FieldChange.Unset(FieldPath.of("bag").child("old\$key")),
        )

        assertEquals(MongoChangeOp.Set(MongoPath("players", 1001L, "bag.a%2Eb"), Document("count", 2)), set)
        assertEquals(MongoChangeOp.Unset(MongoPath("players", 1001L, "bag.old%24key")), unset)
    }

    @Test
    fun `hashes maps independent from insertion order`() {
        val first = linkedMapOf("b" to 2, "a" to 1)
        val second = linkedMapOf("a" to 1, "b" to 2)

        assertEquals(MongoStableHasher.hash(first), MongoStableHasher.hash(second))
    }

    @Test
    fun `generated mongo scan helpers detect map key changes`() {
        val plan = mongoScanPlan(
            mongoScannedField<TestEntity>("name") { entity -> entity.name },
            mongoScannedMapField<TestEntity>("bag") { entity -> entity.bag },
        )
        val before = TestEntity("alice", mapOf("a" to 1, "b" to 2))
        val after = TestEntity("alice", mapOf("a" to 5))

        val changes = plan.diff(plan.capture(before), plan.capture(after), after)

        assertEquals(
            listOf(
                FieldChange.Unset(FieldPath.of("bag").child("b")),
                FieldChange.Set(FieldPath.of("bag").child("a"), 5),
            ),
            changes,
        )
    }

    private data class TestEntity(
        val name: String,
        val bag: Map<String, Int>,
    )
}
