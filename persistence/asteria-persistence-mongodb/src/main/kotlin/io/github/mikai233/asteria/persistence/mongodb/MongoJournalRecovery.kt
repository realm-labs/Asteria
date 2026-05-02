package io.github.mikai233.asteria.persistence.mongodb

import com.mongodb.kotlin.client.coroutine.MongoDatabase

/**
 * Replays uncheckpointed local journal entries into Mongo.
 *
 * The same [MongoWriteJournal] should be reused by the dirty write runtimes after recovery, otherwise recovery and
 * runtime writes can acknowledge different checkpoint state.
 */
class MongoJournalRecovery(
    private val journal: MongoWriteJournal,
    private val database: MongoDatabase,
) {
    suspend fun recover(): MongoJournalRecoveryResult {
        val entries = journal.recover()
        if (entries.isEmpty()) return MongoJournalRecoveryResult()

        val queue = MongoPendingWriteQueue(journal = NoopMongoWriteJournal)
        entries.forEach(queue::replay)
        val writes = queue.snapshot()
        MongoPendingWriteFlusher(queue, database, journal).flush()
        return MongoJournalRecoveryResult(
            entries = entries.size,
            documents = writes.size,
        )
    }
}

data class MongoJournalRecoveryResult(
    val entries: Int = 0,
    val documents: Int = 0,
)

/**
 * Node-level Mongo persistence WAL runtime.
 *
 * Create one instance per node/database, call [recoverOnStart] before actors start loading mutable data, then pass
 * [journal] to Mongo tracked documents and tables.
 */
class MongoJournalRuntime(
    val journal: MongoWriteJournal,
    private val database: MongoDatabase,
    private val policy: MongoJournalPolicy,
) : AutoCloseable {
    suspend fun recoverOnStart(): MongoJournalRecoveryResult {
        if (!policy.enabled || !policy.recoverOnStart) return MongoJournalRecoveryResult()
        return MongoJournalRecovery(journal, database).recover()
    }

    override fun close() {
        journal.close()
    }
}

fun MongoJournalPolicy.createRuntime(database: MongoDatabase): MongoJournalRuntime {
    return MongoJournalRuntime(createJournal(), database, this)
}
