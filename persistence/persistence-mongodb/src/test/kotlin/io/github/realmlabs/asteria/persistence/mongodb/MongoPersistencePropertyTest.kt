package io.github.realmlabs.asteria.persistence.mongodb

import net.jqwik.api.*
import org.bson.Document
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MongoPersistencePropertyTest {
    @Property(tries = 300)
    fun `path segment encoding is reversible`(@ForAll("pathSegments") segment: String) {
        val encoded = MongoPath.encodePathPart(segment)

        assertEquals(segment, MongoPath.decodePathPart(encoded))
        assertFalse(encoded.isEmpty())
        assertFalse(encoded.contains('.'))
        assertFalse(encoded.contains('$'))
        assertFalse(encoded.contains('\u0000'))
    }

    @Property(tries = 200)
    fun `mongo map value recursively encodes unsafe keys`(@ForAll("nestedMaps") value: Map<String, Any>) {
        val document = mongoValueOf(value)

        assertTrue(document is Document)
        assertMongoDocumentKeysSafe(document)
    }

    @Property(tries = 200)
    fun `stable hasher ignores map and set insertion order`(@ForAll("scalarMaps") value: Map<String, Int>) {
        val orderedMap = LinkedHashMap(value)
        val reversedMap = value.entries.toList().asReversed()
            .associateTo(linkedMapOf()) { (key, childValue) -> key to childValue }
        val orderedSet = value.keys.toCollection(linkedSetOf())
        val reversedSet = value.keys.toList().asReversed().toCollection(linkedSetOf())

        assertEquals(MongoStableHasher.hash(orderedMap), MongoStableHasher.hash(reversedMap))
        assertEquals(MongoStableHasher.hash(orderedSet), MongoStableHasher.hash(reversedSet))
    }

    @Property(tries = 300)
    fun `pending write queue matches reference merge model`(@ForAll("queueOperations") operations: List<QueueOperation>) {
        val key = MongoDocumentKey("players", 1001)
        val queue = MongoPendingWriteQueue()
        val reference = ReferencePatch()

        operations.forEach { operation ->
            when (operation) {
                QueueOperation.Delete -> {
                    queue.enqueue(MongoChangeOp.Delete(key))
                    reference.delete()
                }

                is QueueOperation.Set -> {
                    queue.enqueue(MongoChangeOp.Set(key.path(operation.path), operation.value))
                    reference.set(operation.path, operation.value)
                }

                is QueueOperation.Unset -> {
                    queue.enqueue(MongoChangeOp.Unset(key.path(operation.path)))
                    reference.unset(operation.path)
                }
            }
        }

        val expected = reference.toWrite(key)
        val actual = queue.snapshot().singleOrNull()

        if (expected.empty) {
            assertEquals(null, actual)
        } else {
            assertEquals(expected, actual)
            assertEquals(expected, queue.snapshot().single())
            assertEquals(expected, queue.drain().single())
            assertTrue(queue.drain().isEmpty())
        }
    }

    @Provide
    fun pathSegments(): Arbitrary<String> {
        return Arbitraries.strings()
            .withChars('a', 'b', 'c', '.', '$', '%', '\u0000', '中', ' ')
            .ofMinLength(0)
            .ofMaxLength(16)
    }

    @Provide
    fun scalarMaps(): Arbitrary<Map<String, Int>> {
        return Arbitraries.maps(
            pathSegments(),
            Arbitraries.integers().between(-10_000, 10_000),
        ).ofMaxSize(24)
    }

    @Provide
    fun nestedMaps(): Arbitrary<Map<String, Any>> {
        val leaf = Arbitraries.oneOf<Any>(
            Arbitraries.integers().between(-100, 100),
            Arbitraries.strings().withChars('x', 'y', '.', '$', '%', '\u0000').ofMaxLength(8),
        )
        val childMap = Arbitraries.maps(pathSegments(), leaf).ofMaxSize(6)
        val childList = leaf.list().ofMaxSize(6)
        val value = Arbitraries.oneOf(leaf, childMap, childList)
        return Arbitraries.maps(pathSegments(), value).ofMaxSize(10)
    }

    @Provide
    fun queueOperations(): Arbitrary<List<QueueOperation>> {
        val paths = Arbitraries.of(
            "profile",
            "profile.name",
            "profile.level",
            "bag",
            "bag.a",
            "bag.a.count",
            "bag.b",
            "tags",
        )
        val setOperation = paths.flatMap { path ->
            Arbitraries.integers().between(-100, 100)
                .map<QueueOperation> { value -> QueueOperation.Set(path, value) }
        }
        val unsetOperation = paths.map<QueueOperation> { path -> QueueOperation.Unset(path) }
        val deleteOperation = Arbitraries.just<QueueOperation>(QueueOperation.Delete)

        return Arbitraries.oneOf(setOperation, unsetOperation, deleteOperation)
            .list()
            .ofMinSize(1)
            .ofMaxSize(80)
    }

    private fun assertMongoDocumentKeysSafe(value: Any?) {
        when (value) {
            is Document -> value.forEach { (key, childValue) ->
                assertFalse(key.isEmpty())
                assertFalse(key.contains('.'))
                assertFalse(key.contains('$'))
                assertFalse(key.contains('\u0000'))
                assertMongoDocumentKeysSafe(childValue)
            }

            is List<*> -> value.forEach(::assertMongoDocumentKeysSafe)
        }
    }

    sealed interface QueueOperation {
        data class Set(val path: String, val value: Int) : QueueOperation
        data class Unset(val path: String) : QueueOperation
        data object Delete : QueueOperation
    }

    private class ReferencePatch {
        private val sets: MutableMap<String, Any?> = linkedMapOf()
        private val unsets: MutableSet<String> = linkedSetOf()
        private var delete: Boolean = false

        fun set(path: String, value: Any?) {
            if (delete || hasAncestorOperation(path)) return
            removeDescendantOperations(path)
            unsets.remove(path)
            sets[path] = value
        }

        fun unset(path: String) {
            if (delete || hasAncestorOperation(path)) return
            removeDescendantOperations(path)
            sets.remove(path)
            unsets.add(path)
        }

        fun delete() {
            delete = true
            sets.clear()
            unsets.clear()
        }

        fun toWrite(key: MongoDocumentKey): MongoPendingWrite {
            return MongoPendingWrite(
                key = key,
                sets = sets.toMap(),
                unsets = unsets.toSet(),
                delete = delete,
            )
        }

        private fun hasAncestorOperation(path: String): Boolean {
            return ancestors(path).any { ancestor -> ancestor in sets || ancestor in unsets }
        }

        private fun removeDescendantOperations(path: String) {
            val prefix = "$path."
            sets.keys.removeIf { key -> key.startsWith(prefix) }
            unsets.removeIf { key -> key.startsWith(prefix) }
        }

        private fun ancestors(path: String): Sequence<String> = sequence {
            var index = path.lastIndexOf('.')
            while (index > 0) {
                yield(path.substring(0, index))
                index = path.lastIndexOf('.', index - 1)
            }
        }
    }
}
