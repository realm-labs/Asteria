package io.github.mikai233.asteria.persistence.mongodb

import org.bson.Document
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MongoPendingWriteQueueTest {
    @Test
    fun `queue merges child updates into ancestor set`() {
        val queue = MongoPendingWriteQueue()
        val root = MongoPath("player", 1001L, "profile")

        queue.enqueue(MongoChangeOp.Set(root.child("name"), "before"))
        queue.enqueue(MongoChangeOp.Set(root, mapOf("name" to "after", "level" to 1)))
        queue.enqueue(MongoChangeOp.Set(root.child("level"), 2))

        val write = queue.drain().single()

        assertEquals(MongoDocumentKey("player", 1001L), write.key)
        assertEquals(setOf("profile"), write.sets.keys)
        assertEquals(Document(mapOf("name" to "after", "level" to 1)), write.sets["profile"])
        assertTrue(write.unsets.isEmpty())
    }

    @Test
    fun `queue encodes map keys and merges increments`() {
        val queue = MongoPendingWriteQueue()
        val path = MongoPath("player", 1001L, "counters")

        queue.enqueue(MongoChangeOp.Set(path.child("a.b"), 1))
        queue.enqueue(MongoChangeOp.Inc(path.child("login"), 1))
        queue.enqueue(MongoChangeOp.Inc(path.child("login"), 2L))

        val write = queue.drain().single()

        assertEquals(1, write.sets["counters.a%2Eb"])
        assertEquals(3L, write.incs["counters.login"])
    }

    @Test
    fun `requeue preserves pending writes`() {
        val queue = MongoPendingWriteQueue()
        val write = MongoPendingWrite(
            key = MongoDocumentKey("player", 1001L),
            sets = mapOf("level" to 2),
            unsets = setOf("nickname"),
            incs = mapOf("version" to 1),
        )

        queue.requeue(listOf(write))

        assertEquals(write, queue.drain().single())
    }
}
