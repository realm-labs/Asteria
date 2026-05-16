package io.github.realmlabs.asteria.persistence.mongodb.tracked

import io.github.realmlabs.asteria.persistence.mongodb.common.MongoPath
import io.github.realmlabs.asteria.persistence.mongodb.common.MongoPersistentValue
import io.github.realmlabs.asteria.persistence.mongodb.common.mongoValueOf
import io.github.realmlabs.asteria.persistence.mongodb.write.MongoChangeQueue
import org.bson.Document
import kotlin.reflect.KProperty

class MongoTrackedMapDelegate<K, V>(
    trackedMap: MongoTrackedMutableMap<K, V>,
) {
    private val value: MutableMap<K, V> = trackedMap

    operator fun getValue(thisRef: Any?, property: KProperty<*>): MutableMap<K, V> = value
}

/**
 * Tracks map writes with per-key `$set`/`$unset` operations when possible.
 *
 * Map keys are encoded with [MongoPath.encodePathPart]. Mutating a nested value is tracked by wrapping that value, or by
 * rewriting the nearest dirty boundary for structures that cannot produce stable descendant updates.
 */
fun <K, V> mongoTrackedMap(
    path: MongoPath,
    initialValue: MutableMap<K, V>,
    queue: MongoChangeQueue,
    persistentValue: (V) -> Any? = ::mongoValueOf,
    trackedValue: ((K, V) -> V)? = null,
    dirtyTarget: MongoDirtyTarget? = null,
    dirtyTargetProvider: (() -> MongoDirtyTarget?)? = null,
): MongoTrackedMapDelegate<K, V> {
    return MongoTrackedMapDelegate(
        MongoTrackedMutableMap(
            path,
            initialValue,
            queue,
            persistentValue,
            trackedValue,
            dirtyTarget,
            dirtyTargetProvider
        ),
    )
}

/**
 * MutableMap implementation that mirrors ordinary map semantics while recording Mongo patch operations.
 */
class MongoTrackedMutableMap<K, V>(
    private val path: MongoPath,
    initialValue: MutableMap<K, V>,
    private val queue: MongoChangeQueue,
    private val persistentValue: (V) -> Any? = ::mongoValueOf,
    private val trackedValue: ((K, V) -> V)? = null,
    private val dirtyTarget: MongoDirtyTarget? = null,
    private val dirtyTargetProvider: (() -> MongoDirtyTarget?)? = null,
) : AbstractMutableMap<K, V>(), MongoPersistentValue {
    private val backing: MutableMap<K, V> = initialValue

    init {
        backing.entries.toList().forEach { (key, value) ->
            backing[key] = trackValue(key, value)
        }
    }

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = TrackedEntrySet()

    override val size: Int
        get() = backing.size

    override fun containsKey(key: K): Boolean = backing.containsKey(key)

    override fun containsValue(value: V): Boolean = backing.containsValue(value)

    override fun get(key: K): V? = backing[key]

    override fun put(key: K, value: V): V? {
        val valueToStore = trackValue(key, value)
        val existed = backing.containsKey(key)
        val previous = backing.put(key, valueToStore)
        if (!existed || mongoValueOf(previous) != mongoValueOf(valueToStore)) {
            queue.enqueueSet(path.child(key), valueToStore, currentDirtyTarget())
        }
        return previous
    }

    override fun putAll(from: Map<out K, V>) {
        from.forEach { (key, value) -> put(key, value) }
    }

    override fun remove(key: K): V? {
        val existed = backing.containsKey(key)
        val previous = backing.remove(key)
        if (existed) {
            queue.enqueueUnset(path.child(key), currentDirtyTarget())
        }
        return previous
    }

    override fun clear() {
        if (backing.isEmpty()) return
        backing.clear()
        queue.enqueueSet(path, emptyMap<K, V>(), currentDirtyTarget())
    }

    override fun toMongoValue(): Any {
        return Document(
            backing.entries.associate { (key, value) ->
                MongoPath.encodePathPart(key) to persistentValue(value)
            },
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun trackValue(key: K, value: V): V {
        return trackedValue?.invoke(key, value)
            ?: trackMongoMutableValue(path.child(key), value, queue, currentDirtyTarget()) as V
    }

    private fun currentDirtyTarget(): MongoDirtyTarget? = dirtyTargetProvider?.invoke() ?: dirtyTarget

    private inner class TrackedEntrySet : AbstractMutableSet<MutableMap.MutableEntry<K, V>>() {
        override val size: Int
            get() = backing.entries.size

        override fun add(element: MutableMap.MutableEntry<K, V>): Boolean {
            throw UnsupportedOperationException("MutableMap entries do not support add")
        }

        override fun contains(element: MutableMap.MutableEntry<K, V>): Boolean {
            return backing.entries.any { it.key == element.key && it.value == element.value }
        }

        override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> {
            return TrackedEntryIterator(backing.entries.iterator())
        }

        override fun remove(element: MutableMap.MutableEntry<K, V>): Boolean {
            if (!contains(element)) return false
            this@MongoTrackedMutableMap.remove(element.key)
            return true
        }
    }

    private inner class TrackedEntryIterator(
        private val iterator: MutableIterator<MutableMap.MutableEntry<K, V>>,
    ) : MutableIterator<MutableMap.MutableEntry<K, V>> {
        private var current: MutableMap.MutableEntry<K, V>? = null

        override fun hasNext(): Boolean = iterator.hasNext()

        override fun next(): MutableMap.MutableEntry<K, V> {
            val next = iterator.next()
            current = next
            return TrackedEntry(next)
        }

        override fun remove() {
            val entry = requireNotNull(current) { "next() must be called before remove()" }
            iterator.remove()
            queue.enqueueUnset(path.child(entry.key), currentDirtyTarget())
            current = null
        }
    }

    private inner class TrackedEntry(
        private val entry: MutableMap.MutableEntry<K, V>,
    ) : MutableMap.MutableEntry<K, V> {
        override val key: K
            get() = entry.key

        override val value: V
            get() = entry.value

        override fun setValue(newValue: V): V {
            val valueToStore = trackValue(key, newValue)
            val previous = entry.setValue(valueToStore)
            if (mongoValueOf(previous) != mongoValueOf(valueToStore)) {
                queue.enqueueSet(path.child(key), valueToStore, currentDirtyTarget())
            }
            return previous
        }
    }
}

class MongoTrackedListDelegate<E>(
    trackedList: MongoTrackedMutableList<E>,
) {
    private val value: MutableList<E> = trackedList

    operator fun getValue(thisRef: Any?, property: KProperty<*>): MutableList<E> = value
}

/**
 * Tracks mutable list writes while preserving Mongo's positional update constraints.
 *
 * Element replacement writes a positional field when the index is stable. Insertions, removals, and clear rewrite the
 * whole list because subsequent element indexes may shift.
 */
fun <E> mongoTrackedList(
    path: MongoPath,
    initialValue: MutableList<E>,
    queue: MongoChangeQueue,
    persistentValue: (E) -> Any? = ::mongoValueOf,
    trackedValue: ((Int, E) -> E)? = null,
    dirtyTarget: MongoDirtyTarget? = null,
    dirtyTargetProvider: (() -> MongoDirtyTarget?)? = null,
): MongoTrackedListDelegate<E> {
    return MongoTrackedListDelegate(
        MongoTrackedMutableList(
            path,
            initialValue,
            queue,
            persistentValue,
            trackedValue,
            dirtyTarget,
            dirtyTargetProvider
        ),
    )
}

/**
 * MutableList implementation that records Mongo writes for in-place list changes.
 */
class MongoTrackedMutableList<E>(
    private val path: MongoPath,
    initialValue: MutableList<E>,
    private val queue: MongoChangeQueue,
    private val persistentValue: (E) -> Any? = ::mongoValueOf,
    private val trackedValue: ((Int, E) -> E)? = null,
    private val dirtyTarget: MongoDirtyTarget? = null,
    private val dirtyTargetProvider: (() -> MongoDirtyTarget?)? = null,
) : AbstractMutableList<E>(), MongoPersistentValue {
    private val backing: MutableList<E> = initialValue

    init {
        val iterator = backing.listIterator()
        while (iterator.hasNext()) {
            val index = iterator.nextIndex()
            iterator.set(trackValue(index, iterator.next()))
        }
    }

    override val size: Int
        get() = backing.size

    override fun get(index: Int): E = backing[index]

    override fun set(index: Int, element: E): E {
        val valueToStore = trackValue(index, element)
        val previous = backing.set(index, valueToStore)
        if (mongoValueOf(previous) != mongoValueOf(valueToStore)) {
            queue.enqueueSet(path.child(index), valueToStore, currentDirtyTarget())
        }
        return previous
    }

    override fun add(index: Int, element: E) {
        backing.add(index, trackValue(index, element))
        queue.enqueueSet(path, this, currentDirtyTarget())
    }

    override fun removeAt(index: Int): E {
        val previous = backing.removeAt(index)
        queue.enqueueSet(path, this, currentDirtyTarget())
        return previous
    }

    override fun clear() {
        if (backing.isEmpty()) return
        backing.clear()
        queue.enqueueSet(path, emptyList<E>(), currentDirtyTarget())
    }

    override fun toMongoValue(): Any = backing.map(persistentValue)

    @Suppress("UNCHECKED_CAST")
    private fun trackValue(index: Int, value: E): E {
        return trackedValue?.invoke(index, value)
            ?: trackMongoMutableValue(path.child(index), value, queue, currentDirtyTarget()) as E
    }

    private fun currentDirtyTarget(): MongoDirtyTarget? = dirtyTargetProvider?.invoke() ?: dirtyTarget
}

class MongoTrackedSetDelegate<E>(
    trackedSet: MongoTrackedMutableSet<E>,
) {
    private val value: MutableSet<E> = trackedSet

    operator fun getValue(thisRef: Any?, property: KProperty<*>): MutableSet<E> = value
}

/**
 * Tracks mutable set writes as whole-field updates.
 *
 * Sets are persisted as arrays, so any add/remove/clear rewrites the whole set value.
 */
fun <E> mongoTrackedSet(
    path: MongoPath,
    initialValue: MutableSet<E>,
    queue: MongoChangeQueue,
    persistentValue: (E) -> Any? = ::mongoValueOf,
    trackedValue: ((E) -> E)? = null,
    dirtyTarget: MongoDirtyTarget? = null,
): MongoTrackedSetDelegate<E> {
    return MongoTrackedSetDelegate(
        MongoTrackedMutableSet(path, initialValue, queue, persistentValue, trackedValue, dirtyTarget),
    )
}

/**
 * MutableSet implementation that records whole-field Mongo writes for set changes.
 */
class MongoTrackedMutableSet<E>(
    private val path: MongoPath,
    initialValue: MutableSet<E>,
    private val queue: MongoChangeQueue,
    private val persistentValue: (E) -> Any? = ::mongoValueOf,
    private val trackedValue: ((E) -> E)? = null,
    private val dirtyTarget: MongoDirtyTarget? = null,
) : AbstractMutableSet<E>(), MongoPersistentValue {
    private val backing: MutableSet<E> = linkedSetOf()

    init {
        initialValue.forEach { backing += trackValue(it) }
    }

    override val size: Int
        get() = backing.size

    override fun add(element: E): Boolean {
        val valueToStore = trackValue(element)
        val added = backing.add(valueToStore)
        if (added) {
            queue.enqueueSet(path, this, dirtyTarget)
        }
        return added
    }

    override fun clear() {
        if (backing.isEmpty()) return
        backing.clear()
        queue.enqueueSet(path, emptySet<E>(), dirtyTarget)
    }

    override fun iterator(): MutableIterator<E> {
        return TrackedIterator(backing.iterator())
    }

    override fun toMongoValue(): Any = backing.map(persistentValue)

    @Suppress("UNCHECKED_CAST")
    private fun trackValue(value: E): E {
        return trackedValue?.invoke(value)
            ?: trackMongoMutableValue(path, value, queue, dirtyTarget) as E
    }

    private inner class TrackedIterator(
        private val iterator: MutableIterator<E>,
    ) : MutableIterator<E> {
        override fun hasNext(): Boolean = iterator.hasNext()

        override fun next(): E = iterator.next()

        override fun remove() {
            iterator.remove()
            queue.enqueueSet(path, this@MongoTrackedMutableSet, dirtyTarget)
        }
    }
}
