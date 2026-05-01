package io.github.mikai233.asteria.persistence.mongodb

import org.bson.Document
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `tracked map writes encoded child path`() {
        val queue = MongoPendingWriteQueue()
        val path = MongoPath("player", 1001L, "bag")
        val bag = MongoTrackedMutableMap(path, mutableMapOf<String, Int>(), queue)

        bag["item.1"] = 5

        val write = queue.drain().single()
        assertEquals(mapOf("bag.item%2E1" to 5), write.sets)
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

    override fun toMongoValue(): Any? {
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
) : io.github.mikai233.asteria.persistence.Entity<Long>
