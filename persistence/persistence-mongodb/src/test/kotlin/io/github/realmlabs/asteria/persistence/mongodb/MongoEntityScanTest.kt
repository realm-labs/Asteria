package io.github.realmlabs.asteria.persistence.mongodb

import io.github.realmlabs.asteria.persistence.FieldChange
import io.github.realmlabs.asteria.persistence.FieldPath
import org.bson.Document
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

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
            change = FieldChange.Unset(FieldPath.of("bag").child($$"old$key")),
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
    fun `hashes sets independent from insertion order and lists by order`() {
        val firstSet = linkedSetOf("b", "a")
        val secondSet = linkedSetOf("a", "b")

        assertEquals(MongoStableHasher.hash(firstSet), MongoStableHasher.hash(secondSet))
        assertNotEquals(MongoStableHasher.hash(listOf("b", "a")), MongoStableHasher.hash(listOf("a", "b")))
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

    @Test
    fun `generated mongo scan helpers detect nested object list set and map changes`() {
        val plan = mongoScanPlan(
            mongoScannedField<ComplexEntity>("profile") { entity -> entity.profile },
            mongoScannedMapField<ComplexEntity>("bag") { entity -> entity.bag },
            mongoScannedField<ComplexEntity>("quests") { entity -> entity.quests },
            mongoScannedField<ComplexEntity>("tags") { entity -> entity.tags },
            mongoScannedMapField<ComplexEntity>("counters") { entity -> entity.counters },
        )
        val before = ComplexEntity(
            profile = Profile("alice", 1),
            bag = linkedMapOf("sword" to ItemStack(1001, 1), "potion" to ItemStack(2001, 2)),
            quests = listOf(QuestState(10, 1)),
            tags = linkedSetOf("newbie", "vip"),
            counters = linkedMapOf("win" to 1, "lose" to 2),
        )
        val after = ComplexEntity(
            profile = Profile("bob", 1),
            bag = linkedMapOf("sword" to ItemStack(1001, 3), "shield" to ItemStack(3001, 1)),
            quests = listOf(QuestState(10, 2), QuestState(20, 1)),
            tags = linkedSetOf("vip", "newbie"),
            counters = linkedMapOf("win" to 5),
        )

        val changes = plan.diff(plan.capture(before), plan.capture(after), after)

        assertEquals(
            listOf(
                FieldChange.Unset(FieldPath.of("bag").child("potion")),
                FieldChange.Unset(FieldPath.of("counters").child("lose")),
                FieldChange.Set(FieldPath.of("profile"), after.profile),
                FieldChange.Set(FieldPath.of("bag").child("sword"), ItemStack(1001, 3)),
                FieldChange.Set(FieldPath.of("bag").child("shield"), ItemStack(3001, 1)),
                FieldChange.Set(FieldPath.of("quests"), after.quests),
                FieldChange.Set(FieldPath.of("counters").child("win"), 5),
            ),
            changes,
        )
    }

    @Test
    fun `generated mongo scan helpers detect nullable map removal as child unsets`() {
        val plan = mongoScanPlan(
            mongoScannedMapField<ComplexEntity>("counters") { entity -> entity.counters },
        )
        val before = ComplexEntity(
            profile = Profile("alice", 1),
            bag = emptyMap(),
            quests = emptyList(),
            tags = emptySet(),
            counters = linkedMapOf("win" to 1, "lose" to 2),
        )
        val after = before.copy(counters = null)

        val changes = plan.diff(plan.capture(before), plan.capture(after), after)

        assertEquals(
            listOf(
                FieldChange.Unset(FieldPath.of("counters").child("win")),
                FieldChange.Unset(FieldPath.of("counters").child("lose")),
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

    private data class ComplexEntity(
        val profile: Profile,
        val bag: Map<String, ItemStack>,
        val quests: List<QuestState>,
        val tags: Set<String>,
        val counters: Map<String, Int>?,
    )

    private data class Profile(
        val nickname: String,
        val avatar: Int,
    )

    private data class ItemStack(
        val itemId: Int,
        val count: Int,
    )
}
