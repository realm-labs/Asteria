package io.github.realmlabs.asteria.persistence.mongodb

import io.github.realmlabs.asteria.persistence.mongodb.common.MongoPath
import io.github.realmlabs.asteria.persistence.mongodb.common.mongoValueOf
import io.github.realmlabs.asteria.persistence.mongodb.tracked.*
import io.github.realmlabs.asteria.persistence.mongodb.write.MongoPendingWriteQueue
import org.bson.Document
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MongoTrackedValueTest {
    @Test
    fun `tracked scalar writes field set`() {
        val queue = MongoPendingWriteQueue()
        val state = TestState(MongoTrackContext("player", 1001L, queue), "alice", 1)

        state.level = 2

        val write = queue.drain().single()
        assertEquals(mapOf("level" to 2), write.sets)
    }

    @Test
    fun `tracked scalar ignores equal value`() {
        val queue = MongoPendingWriteQueue()
        val state = TestState(MongoTrackContext("player", 1001L, queue), "alice", 1)

        state.level = 1

        assertTrue(queue.drain().isEmpty())
    }

    @Test
    fun `tracked map writes encoded child path`() {
        val queue = MongoPendingWriteQueue()
        val path = MongoPath("player", 1001L, "bag")
        val bag = MongoTrackedMutableMap(path, mutableMapOf<String, Int>(), queue)

        bag["item.1"] = 5

        val write = queue.drain().single()
        assertEquals(mapOf("bag.item%2E1" to 5), write.sets)
    }

    @Test
    fun `tracked map remove writes child unset`() {
        val queue = MongoPendingWriteQueue()
        val bag = MongoTrackedMutableMap(MongoPath("player", 1001L, "bag"), mutableMapOf("item.1" to 5), queue)

        bag.remove("item.1")

        val write = queue.drain().single()
        assertEquals(setOf("bag.item%2E1"), write.unsets)
    }

    @Test
    fun `tracked map entry setValue writes child set`() {
        val queue = MongoPendingWriteQueue()
        val bag = MongoTrackedMutableMap(MongoPath("player", 1001L, "bag"), mutableMapOf("item" to 5), queue)

        bag.entries.first().setValue(7)

        val write = queue.drain().single()
        assertEquals(mapOf("bag.item" to 7), write.sets)
    }

    @Test
    fun `tracked map mutable views write encoded child unsets`() {
        val queue = MongoPendingWriteQueue()
        val bag = MongoTrackedMutableMap(
            MongoPath("player", 1001L, "bag"),
            linkedMapOf(
                "item.1" to 5,
                "$.cash" to 10,
                "%raw" to 15,
            ),
            queue,
        )

        bag.entries.iterator().apply {
            assertEquals("item.1", next().key)
            remove()
        }
        bag.keys.remove("$.cash")
        bag.values.remove(15)

        val write = queue.drain().single()
        assertEquals(
            setOf("bag.item%2E1", "bag.%24%2Ecash", "bag.%25raw"),
            write.unsets,
        )
    }

    @Test
    fun `tracked map clear writes empty document`() {
        val queue = MongoPendingWriteQueue()
        val bag = MongoTrackedMutableMap(MongoPath("player", 1001L, "bag"), mutableMapOf("item" to 5), queue)

        bag.clear()

        val write = queue.drain().single()
        assertEquals(Document(), write.sets["bag"])
    }

    @Test
    fun `tracked list structural changes write whole list`() {
        val queue = MongoPendingWriteQueue()
        val list = MongoTrackedMutableList(MongoPath("player", 1001L, "rewards"), mutableListOf(1), queue)

        list.add(2)

        val write = queue.drain().single()
        assertEquals(mapOf("rewards" to listOf(1, 2)), write.sets)
    }

    @Test
    fun `tracked list element set writes indexed path`() {
        val queue = MongoPendingWriteQueue()
        val list = MongoTrackedMutableList(MongoPath("player", 1001L, "rewards"), mutableListOf(1), queue)

        list[0] = 3

        val write = queue.drain().single()
        assertEquals(mapOf("rewards.0" to 3), write.sets)
    }

    @Test
    fun `tracked list iterator structural changes write whole list`() {
        val queue = MongoPendingWriteQueue()
        val list = MongoTrackedMutableList(MongoPath("player", 1001L, "rewards"), mutableListOf(1, 2, 3), queue)
        val iterator = list.listIterator(1)

        assertEquals(2, iterator.next())
        iterator.remove()
        iterator.add(4)

        val write = queue.drain().single()
        assertEquals(mapOf("rewards" to listOf(1, 4, 3)), write.sets)
    }

    @Test
    fun `tracked list sublist structural changes write whole list`() {
        val queue = MongoPendingWriteQueue()
        val list = MongoTrackedMutableList(MongoPath("player", 1001L, "rewards"), mutableListOf(1, 2, 3, 4), queue)

        list.subList(1, 3).removeAt(0)

        val write = queue.drain().single()
        assertEquals(mapOf("rewards" to listOf(1, 3, 4)), write.sets)
    }

    @Test
    fun `tracked list remove writes whole list`() {
        val queue = MongoPendingWriteQueue()
        val list = MongoTrackedMutableList(MongoPath("player", 1001L, "rewards"), mutableListOf(1, 2), queue)

        list.removeAt(0)

        val write = queue.drain().single()
        assertEquals(mapOf("rewards" to listOf(2)), write.sets)
    }

    @Test
    fun `tracked set structural changes write whole set`() {
        val queue = MongoPendingWriteQueue()
        val set = MongoTrackedMutableSet(MongoPath("player", 1001L, "tags"), linkedSetOf("a"), queue)

        set.add("b")

        val write = queue.drain().single()
        assertEquals(mapOf("tags" to listOf("a", "b")), write.sets)
    }

    @Test
    fun `tracked set remove writes whole set`() {
        val queue = MongoPendingWriteQueue()
        val set = MongoTrackedMutableSet(MongoPath("player", 1001L, "tags"), linkedSetOf("a", "b"), queue)

        set.remove("a")

        val write = queue.drain().single()
        assertEquals(mapOf("tags" to listOf("b")), write.sets)
    }

    @Test
    fun `tracked set iterator remove and clear write whole set`() {
        val queue = MongoPendingWriteQueue()
        val set = MongoTrackedMutableSet(MongoPath("player", 1001L, "tags"), linkedSetOf("a", "b", "c"), queue)

        val iterator = set.iterator()
        assertEquals("a", iterator.next())
        iterator.remove()
        queue.drain().single().also { write ->
            assertEquals(mapOf("tags" to listOf("b", "c")), write.sets)
        }

        set.clear()

        val write = queue.drain().single()
        assertEquals(mapOf("tags" to emptyList<String>()), write.sets)
    }

    @Test
    fun `deep mutable value is promoted to dirty boundary`() {
        val queue = MongoPendingWriteQueue()
        val root = MongoPath("player", 1001L, "data.inventory.items")

        @Suppress("UNCHECKED_CAST")
        val items = trackMongoMutableValue(root, mutableMapOf("1" to mutableMapOf("count" to 1)), queue)
                as MutableMap<String, MutableMap<String, Int>>

        items.getValue("1")["count"] = 2

        val write = queue.drain().single()
        assertEquals(setOf("data.inventory.items"), write.sets.keys)
        assertEquals(Document(mapOf("1" to Document(mapOf("count" to 2)))), write.sets["data.inventory.items"])
    }

    @Test
    fun `shallow nested mutable value writes nearest child boundary`() {
        val queue = MongoPendingWriteQueue()
        val root = MongoPath("player", 1001L, "bag")

        @Suppress("UNCHECKED_CAST")
        val bag = trackMongoMutableValue(root, mutableMapOf("1" to mutableMapOf("count" to 1)), queue)
                as MutableMap<String, MutableMap<String, Int>>

        bag.getValue("1")["count"] = 2

        val write = queue.drain().single()
        assertEquals(setOf("bag.1"), write.sets.keys)
        assertEquals(Document(mapOf("count" to 2)), write.sets["bag.1"])
    }

    @Test
    fun `tracked document persistent value uses mongo field names`() {
        val queue = MongoPendingWriteQueue()
        val state = TestState(MongoTrackContext("player", 1001L, queue), "alice", 1)

        assertEquals(
            Document(mapOf("_id" to 1001L, "name" to "alice", "level" to 1)),
            mongoValueOf(state),
        )
    }
}

private class TestState(
    ctx: MongoTrackContext,
    private val name: String,
    level: Int,
) : MongoTrackedDocument<Long, TestEntity> {
    override val id: Long = ctx.documentId as Long

    var level: Int by mongoTrackedValue(ctx.path("level"), level, ctx.queue)

    override fun toMongoValue(): Any {
        return Document(mapOf("_id" to id, "name" to name, "level" to level))
    }

    override fun toEntity(): TestEntity {
        return TestEntity(id, name, level)
    }
}

private data class TestEntity(
    override val id: Long,
    val name: String,
    val level: Int,
) : io.github.realmlabs.asteria.persistence.Entity<Long>
