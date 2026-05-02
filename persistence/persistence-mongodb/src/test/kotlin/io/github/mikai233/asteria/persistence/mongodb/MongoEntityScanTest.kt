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

    @Test
    fun `generated mongo scan helpers detect list field changes as whole value`() {
        val plan = mongoScanPlan(
            mongoScannedField<QuestEntity>("quests") { entity -> entity.quests },
        )
        val before = QuestEntity(listOf(QuestState(1, 1), QuestState(2, 1)))
        val after = QuestEntity(listOf(QuestState(1, 2), QuestState(3, 1)))

        val changes = plan.diff(plan.capture(before), plan.capture(after), after)

        assertEquals(
            listOf(
                FieldChange.Set(FieldPath.of("quests"), after.quests),
            ),
            changes,
        )
    }

    private data class TestEntity(
        val name: String,
        val bag: Map<String, Int>,
    )

    private data class QuestEntity(
        val quests: List<QuestState>,
    )

    private data class QuestState(
        val questId: Int,
        val status: Int,
    )
}
