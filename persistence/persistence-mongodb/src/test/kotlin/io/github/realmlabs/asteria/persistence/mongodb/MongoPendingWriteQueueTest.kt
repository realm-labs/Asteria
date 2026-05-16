package io.github.realmlabs.asteria.persistence.mongodb

import io.github.realmlabs.asteria.persistence.mongodb.common.MongoDocumentKey
import io.github.realmlabs.asteria.persistence.mongodb.common.MongoPath
import io.github.realmlabs.asteria.persistence.mongodb.common.path
import io.github.realmlabs.asteria.persistence.mongodb.write.MongoChangeOp
import io.github.realmlabs.asteria.persistence.mongodb.write.MongoPendingWrite
import io.github.realmlabs.asteria.persistence.mongodb.write.MongoPendingWriteQueue
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
    fun `ancestor unset removes child operations`() {
        val queue = MongoPendingWriteQueue()
        val root = MongoPath("player", 1001L, "profile")

        queue.enqueue(MongoChangeOp.Set(root.child("name"), "alice"))
        queue.enqueue(MongoChangeOp.Set(root.child("version"), 1))
        queue.enqueue(MongoChangeOp.Unset(root))
        queue.enqueue(MongoChangeOp.Set(root.child("level"), 2))

        val write = queue.drain().single()

        assertTrue(write.sets.isEmpty())
        assertEquals(setOf("profile"), write.unsets)
    }

    @Test
    fun `set after unset on same path cancels unset`() {
        val queue = MongoPendingWriteQueue()
        val path = MongoPath("player", 1001L, "nickname")

        queue.enqueue(MongoChangeOp.Unset(path))
        queue.enqueue(MongoChangeOp.Set(path, "alice"))

        val write = queue.drain().single()

        assertEquals(mapOf("nickname" to "alice"), write.sets)
        assertTrue(write.unsets.isEmpty())
    }

    @Test
    fun `queue encodes map keys`() {
        val queue = MongoPendingWriteQueue()
        val path = MongoPath("player", 1001L, "counters")

        queue.enqueue(MongoChangeOp.Set(path.child("a.b"), 1))
        queue.enqueue(MongoChangeOp.Set(path.child($$"a$b"), 2))
        queue.enqueue(MongoChangeOp.Set(path.child("a%b"), 3))

        val write = queue.drain().single()

        assertEquals(1, write.sets["counters.a%2Eb"])
        assertEquals(2, write.sets["counters.a%24b"])
        assertEquals(3, write.sets["counters.a%25b"])
    }

    @Test
    fun `snapshot does not clear queue but drain does`() {
        val queue = MongoPendingWriteQueue()
        queue.enqueue(MongoChangeOp.Set(MongoPath("player", 1001L, "level"), 2))

        assertEquals(1, queue.snapshot().size)
        assertEquals(1, queue.snapshot().size)
        assertEquals(1, queue.drain().size)
        assertTrue(queue.drain().isEmpty())
    }

    @Test
    fun `queue keeps writes for different documents separate`() {
        val queue = MongoPendingWriteQueue()

        queue.enqueue(MongoChangeOp.Set(MongoPath("player", 1001L, "level"), 2))
        queue.enqueue(MongoChangeOp.Set(MongoPath("player", 1002L, "level"), 3))

        val writes = queue.drain().associateBy { it.key.documentId }

        assertEquals(2, writes.getValue(1001L).sets["level"])
        assertEquals(3, writes.getValue(1002L).sets["level"])
    }

    @Test
    fun `requeue preserves pending writes`() {
        val queue = MongoPendingWriteQueue()
        val write = MongoPendingWrite(
            key = MongoDocumentKey("player", 1001L),
            sets = mapOf("level" to 2),
            unsets = setOf("nickname"),
            journalSequences = setOf(1, 2),
        )

        queue.requeue(listOf(write))

        assertEquals(write, queue.drain().single())
    }

    @Test
    fun `delete overrides field updates`() {
        val queue = MongoPendingWriteQueue()
        val key = MongoDocumentKey("player", 1001L)

        queue.enqueue(MongoChangeOp.Set(key.path("level"), 2))
        queue.enqueue(MongoChangeOp.Unset(key.path("nickname")))
        queue.enqueue(MongoChangeOp.Delete(key))
        queue.enqueue(MongoChangeOp.Set(key.path("level"), 3))

        val write = queue.drain().single()

        assertEquals(key, write.key)
        assertTrue(write.delete)
        assertTrue(write.sets.isEmpty())
        assertTrue(write.unsets.isEmpty())
    }

    @Test
    fun `requeue preserves delete writes`() {
        val queue = MongoPendingWriteQueue()
        val write = MongoPendingWrite(
            key = MongoDocumentKey("player", 1001L),
            delete = true,
            journalSequences = setOf(3),
        )

        queue.requeue(listOf(write))

        assertEquals(write, queue.drain().single())
    }

    @Test
    fun `queue notifies dirty callback when writes are enqueued`() {
        var dirtyCount = 0
        val queue = MongoPendingWriteQueue { dirtyCount += 1 }

        queue.enqueue(MongoChangeOp.Set(MongoPath("player", 1001L, "level"), 2))
        queue.enqueue(MongoChangeOp.Unset(MongoPath("player", 1001L, "name")))

        assertEquals(2, dirtyCount)
    }
}
