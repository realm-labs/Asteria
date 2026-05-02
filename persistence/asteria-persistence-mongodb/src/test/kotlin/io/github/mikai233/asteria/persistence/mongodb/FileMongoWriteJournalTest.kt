package io.github.mikai233.asteria.persistence.mongodb

import kotlin.io.path.createTempDirectory
import kotlin.io.path.fileSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileMongoWriteJournalTest {
    @Test
    fun `journal recovers unacknowledged set and unset entries`() {
        val directory = createTempDirectory("asteria-mongo-journal")
        FileMongoWriteJournal(MongoJournalPolicy(enabled = true, directory = directory, forceOnAppend = true)).use { journal ->
            journal.append(MongoChangeOp.Set(MongoPath("player", 1001L, "level"), 2))
            journal.append(MongoChangeOp.Unset(MongoPath("player", 1001L, "nickname")))
        }

        FileMongoWriteJournal(MongoJournalPolicy(enabled = true, directory = directory, forceOnAppend = true)).use { journal ->
            val recovered = journal.recover()

            assertEquals(2, recovered.size)
            assertEquals(MongoChangeOp.Set(MongoPath("player", 1001L, "level"), 2), recovered[0].op)
            assertEquals(MongoChangeOp.Unset(MongoPath("player", 1001L, "nickname")), recovered[1].op)
        }
    }

    @Test
    fun `checkpoint only advances over contiguous acknowledged entries`() {
        val directory = createTempDirectory("asteria-mongo-journal")
        FileMongoWriteJournal(MongoJournalPolicy(enabled = true, directory = directory, forceOnAppend = true)).use { journal ->
            val first = journal.append(MongoChangeOp.Set(MongoPath("player", 1001L, "level"), 2))
            val second = journal.append(MongoChangeOp.Set(MongoPath("player", 1002L, "level"), 3))
            journal.ack(listOf(second))
            assertEquals(listOf(first, second), journal.recover().map { it.sequence })
            journal.ack(listOf(first))
            assertTrue(journal.recover().isEmpty())
        }
    }

    @Test
    fun `queue records journal sequences and replay merges final write`() {
        val directory = createTempDirectory("asteria-mongo-journal")
        FileMongoWriteJournal(MongoJournalPolicy(enabled = true, directory = directory, forceOnAppend = true)).use { journal ->
            val queue = MongoPendingWriteQueue(journal = journal)
            val root = MongoPath("player", 1001L, "profile")

            queue.enqueue(MongoChangeOp.Set(root.child("name"), "alice"))
            queue.enqueue(MongoChangeOp.Unset(root))
            val write = queue.drain().single()

            assertEquals(setOf("profile"), write.unsets)
            assertEquals(2, write.journalSequences.size)
        }

        FileMongoWriteJournal(MongoJournalPolicy(enabled = true, directory = directory, forceOnAppend = true)).use { journal ->
            val recoveredQueue = MongoPendingWriteQueue(journal = NoopMongoWriteJournal)
            journal.recover().forEach(recoveredQueue::replay)

            val recoveredWrite = recoveredQueue.drain().single()

            assertEquals(setOf("profile"), recoveredWrite.unsets)
            assertEquals(2, recoveredWrite.journalSequences.size)
        }
    }

    @Test
    fun `journal compaction keeps unacknowledged entries`() {
        val directory = createTempDirectory("asteria-mongo-journal")
        val policy = MongoJournalPolicy(
            enabled = true,
            directory = directory,
            forceOnAppend = true,
            compactThresholdBytes = 1,
        )
        FileMongoWriteJournal(policy).use { journal ->
            val first = journal.append(MongoChangeOp.Set(MongoPath("player", 1001L, "level"), 2))
            val second = journal.append(MongoChangeOp.Set(MongoPath("player", 1002L, "level"), 3))

            journal.ack(listOf(first))

            assertEquals(listOf(second), journal.recover().map { it.sequence })
            assertTrue(directory.resolve("active.wal").fileSize() > 0)
        }
    }

    @Test
    fun `journal compaction removes fully acknowledged entries`() {
        val directory = createTempDirectory("asteria-mongo-journal")
        val policy = MongoJournalPolicy(
            enabled = true,
            directory = directory,
            forceOnAppend = true,
            compactThresholdBytes = 1,
        )
        FileMongoWriteJournal(policy).use { journal ->
            val first = journal.append(MongoChangeOp.Set(MongoPath("player", 1001L, "level"), 2))
            val second = journal.append(MongoChangeOp.Set(MongoPath("player", 1002L, "level"), 3))

            journal.ack(listOf(first, second))

            assertTrue(journal.recover().isEmpty())
            assertEquals(0, directory.resolve("active.wal").fileSize())
        }
    }

    @Test
    fun `buffered journal flushes pending lines on close`() {
        val directory = createTempDirectory("asteria-mongo-journal")
        val policy = MongoJournalPolicy(
            enabled = true,
            directory = directory,
            writeMode = MongoJournalWriteMode.BUFFERED,
            forceOnAppend = false,
        )
        policy.createJournal().use { journal ->
            val first = requireNotNull(journal.append(MongoChangeOp.Set(MongoPath("player", 1001L, "level"), 2)))
            val second = requireNotNull(journal.append(MongoChangeOp.Unset(MongoPath("player", 1001L, "profile"))))
            journal.ack(listOf(first, second))
        }

        FileMongoWriteJournal(policy.copy(writeMode = MongoJournalWriteMode.SYNC)).use { journal ->
            assertTrue(journal.recover().isEmpty())
        }
    }
}
