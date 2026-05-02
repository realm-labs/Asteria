package io.github.mikai233.asteria.persistence.mongodb

/**
 * One logical change to a Mongo document.
 */
sealed class MongoChangeOp(open val path: MongoPath) {
    data class Set(override val path: MongoPath, val value: Any?) : MongoChangeOp(path)
    data class Unset(override val path: MongoPath) : MongoChangeOp(path)
}

fun interface MongoChangeQueue {
    fun enqueue(op: MongoChangeOp)
}

/**
 * Merged pending update for one Mongo document.
 */
data class MongoPendingWrite(
    val key: MongoDocumentKey,
    val sets: Map<String, Any?> = emptyMap(),
    val unsets: Set<String> = emptySet(),
    val journalSequences: Set<Long> = emptySet(),
) {
    val empty: Boolean
        get() = sets.isEmpty() && unsets.isEmpty()

    val upsert: Boolean
        get() = sets.isNotEmpty()
}

/**
 * Actor-local dirty queue that collapses repeated writes to the same document.
 *
 * This type is not thread-safe. It is expected to be used by one owning actor or row cache on its serialized execution
 * context.
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
        val patch = patches.getOrPut(op.path.key) { PendingMongoPatch() }
        patch.merge(op, journalSequence)
        onDirty()
    }

    fun drain(): List<MongoPendingWrite> {
        val writes = patches.map { (key, patch) -> patch.toWrite(key) }
            .filterNot { it.empty }
        patches.clear()
        return writes
    }

    fun requeue(writes: Iterable<MongoPendingWrite>) {
        writes.forEach { write ->
            val patch = patches.getOrPut(write.key) { PendingMongoPatch() }
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

    fun snapshot(): List<MongoPendingWrite> {
        return patches.map { (key, patch) -> patch.toWrite(key) }
            .filterNot { it.empty }
    }
}

private class PendingMongoPatch {
    private val sets: MutableMap<String, Any?> = linkedMapOf()
    private val unsets: MutableSet<String> = linkedSetOf()
    private val journalSequences: MutableSet<Long> = linkedSetOf()

    fun merge(op: MongoChangeOp, journalSequence: Long?) {
        journalSequence?.let(journalSequences::add)
        when (op) {
            is MongoChangeOp.Set -> set(op.path.fieldPath, op.value)
            is MongoChangeOp.Unset -> unset(op.path.fieldPath)
        }
    }

    fun addJournalSequence(sequence: Long) {
        journalSequences += sequence
    }

    private fun set(path: String, value: Any?) {
        if (hasAncestorOperation(path)) return
        removeDescendantOperations(path)
        unsets.remove(path)
        sets[path] = value
    }

    private fun unset(path: String) {
        if (hasAncestorOperation(path)) return
        removeDescendantOperations(path)
        sets.remove(path)
        unsets.add(path)
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
            journalSequences = journalSequences.toSet(),
        )
    }
}
