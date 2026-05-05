package io.github.realmlabs.asteria.persistence.mongodb

/**
 * One dirty operation against a Mongo document.
 *
 * [Set] and [Unset] operate on a field path inside one document. [Delete] removes the whole document and suppresses any
 * pending field updates for the same key.
 */
sealed class MongoChangeOp {
    abstract val key: MongoDocumentKey

    data class Set(val path: MongoPath, val value: Any?) : MongoChangeOp() {
        override val key: MongoDocumentKey
            get() = path.key
    }

    data class Unset(val path: MongoPath) : MongoChangeOp() {
        override val key: MongoDocumentKey
            get() = path.key
    }

    data class Delete(override val key: MongoDocumentKey) : MongoChangeOp()
}

fun interface MongoChangeQueue {
    fun enqueue(op: MongoChangeOp)
}

/**
 * Merged pending update for one Mongo document.
 *
 * [journalSequences] tracks the journal entries covered by this write so a successful flush can acknowledge only the
 * entries that actually reached Mongo. Requeued writes keep their existing sequence ids and do not append new journal
 * entries.
 */
data class MongoPendingWrite(
    val key: MongoDocumentKey,
    val sets: Map<String, Any?> = emptyMap(),
    val unsets: Set<String> = emptySet(),
    val delete: Boolean = false,
    val journalSequences: Set<Long> = emptySet(),
) {
    val empty: Boolean
        get() = !delete && sets.isEmpty() && unsets.isEmpty()

    val upsert: Boolean
        get() = !delete && sets.isNotEmpty()
}

/**
 * Actor-local dirty queue that collapses repeated writes to the same document.
 *
 * This type is not thread-safe. It is expected to be used by one owning actor or row cache on its serialized execution
 * context. Field-path merges are hierarchical: a set or unset on `profile` makes later operations on `profile.name`
 * redundant, and a later set or unset on `profile` removes earlier descendant operations.
 */
class MongoPendingWriteQueue(
    private val journal: MongoWriteJournal = NoopMongoWriteJournal,
    private val onDirty: () -> Unit = {},
) : MongoChangeQueue {
    private val patches: MutableMap<MongoDocumentKey, PendingMongoPatch> = linkedMapOf()

    override fun enqueue(op: MongoChangeOp) {
        enqueue(op, journal.append(op))
    }

    fun replay(entry: MongoJournalEntry) {
        enqueue(entry.op, entry.sequence)
    }

    private fun enqueue(op: MongoChangeOp, journalSequence: Long?) {
        val patch = patches.getOrPut(op.key) { PendingMongoPatch() }
        patch.merge(op, journalSequence)
        onDirty()
    }

    /**
     * Returns the current merged writes and clears the queue.
     */
    fun drain(): List<MongoPendingWrite> {
        val writes = patches.map { (key, patch) -> patch.toWrite(key) }
            .filterNot { it.empty }
        patches.clear()
        return writes
    }

    /**
     * Puts failed writes back at the tail without appending new journal entries.
     */
    fun requeue(writes: Iterable<MongoPendingWrite>) {
        writes.forEach { write ->
            val patch = patches.getOrPut(write.key) { PendingMongoPatch() }
            if (write.delete) {
                patch.merge(MongoChangeOp.Delete(write.key), null)
            }
            write.sets.forEach { (fieldPath, value) ->
                patch.merge(MongoChangeOp.Set(write.key.path(fieldPath), value), null)
            }
            write.unsets.forEach { fieldPath ->
                patch.merge(MongoChangeOp.Unset(write.key.path(fieldPath)), null)
            }
            write.journalSequences.forEach(patch::addJournalSequence)
            onDirty()
        }
    }

    /**
     * Returns the current merged writes without clearing the queue.
     */
    fun snapshot(): List<MongoPendingWrite> {
        return patches.map { (key, patch) -> patch.toWrite(key) }
            .filterNot { it.empty }
    }
}

private class PendingMongoPatch {
    private val sets: MutableMap<String, Any?> = linkedMapOf()
    private val unsets: MutableSet<String> = linkedSetOf()
    private val journalSequences: MutableSet<Long> = linkedSetOf()
    private var delete: Boolean = false

    fun merge(op: MongoChangeOp, journalSequence: Long?) {
        journalSequence?.let(journalSequences::add)
        when (op) {
            is MongoChangeOp.Set -> set(op.path.fieldPath, op.value)
            is MongoChangeOp.Unset -> unset(op.path.fieldPath)
            is MongoChangeOp.Delete -> delete()
        }
    }

    fun addJournalSequence(sequence: Long) {
        journalSequences += sequence
    }

    private fun set(path: String, value: Any?) {
        if (delete) return
        if (hasAncestorOperation(path)) return
        removeDescendantOperations(path)
        unsets.remove(path)
        sets[path] = value
    }

    private fun unset(path: String) {
        if (delete) return
        if (hasAncestorOperation(path)) return
        removeDescendantOperations(path)
        sets.remove(path)
        unsets.add(path)
    }

    private fun delete() {
        delete = true
        sets.clear()
        unsets.clear()
    }

    private fun hasAncestorOperation(path: String): Boolean {
        return ancestors(path).any { ancestor -> ancestor in sets || ancestor in unsets }
    }

    private fun removeDescendantOperations(path: String) {
        val prefix = "$path."
        sets.keys.removeIf { it.startsWith(prefix) }
        unsets.removeIf { it.startsWith(prefix) }
    }

    private fun ancestors(path: String): Sequence<String> = sequence {
        var index = path.lastIndexOf('.')
        while (index > 0) {
            yield(path.substring(0, index))
            index = path.lastIndexOf('.', index - 1)
        }
    }

    fun toWrite(key: MongoDocumentKey): MongoPendingWrite {
        return MongoPendingWrite(
            key = key,
            sets = sets.mapValues { (_, value) -> mongoValueOf(value) },
            unsets = unsets.toSet(),
            delete = delete,
            journalSequences = journalSequences.toSet(),
        )
    }
}
