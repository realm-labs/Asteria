package io.github.realmlabs.asteria.persistence.mongodb

import io.github.realmlabs.asteria.persistence.DataLease
import io.github.realmlabs.asteria.persistence.DataLeaseAware
import java.util.*
import kotlin.reflect.KProperty

private const val DEFAULT_WRITE_BOUNDARY_DEPTH: Int = 2

/**
 * Boundary used when an inner mutable object cannot produce stable field-level updates.
 */
data class MongoDirtyTarget(
    val path: MongoPath,
    val value: Any?,
)

interface MongoDirtyTargetAware {
    fun bindDirtyTarget(dirtyTarget: MongoDirtyTarget?)
}

internal fun MongoChangeQueue.enqueueSet(path: MongoPath, value: Any?, dirtyTarget: MongoDirtyTarget?) {
    if (dirtyTarget == null) {
        enqueue(MongoChangeOp.Set(path, value))
    } else {
        enqueue(MongoChangeOp.Set(dirtyTarget.path, dirtyTarget.value))
    }
}

internal fun MongoChangeQueue.enqueueUnset(path: MongoPath, dirtyTarget: MongoDirtyTarget?) {
    if (dirtyTarget == null) {
        enqueue(MongoChangeOp.Unset(path))
    } else {
        enqueue(MongoChangeOp.Set(dirtyTarget.path, dirtyTarget.value))
    }
}

@Suppress("UNCHECKED_CAST")
fun trackMongoMutableValue(
    path: MongoPath,
    value: Any?,
    queue: MongoChangeQueue,
    dirtyTarget: MongoDirtyTarget? = null,
): Any? {
    val effectiveDirtyTarget = dirtyTarget ?: writeBoundaryFor(path, value)
    if (value is MongoDirtyTargetAware) {
        value.bindDirtyTarget(effectiveDirtyTarget)
        return value
    }
    return when (value) {
        is MongoPersistentValue -> value
        is MutableMap<*, *> -> MongoTrackedMutableMap(
            path = path,
            initialValue = value as MutableMap<Any?, Any?>,
            queue = queue,
            dirtyTarget = effectiveDirtyTarget,
        )

        is MutableList<*> -> MongoTrackedMutableList(
            path = path,
            initialValue = value as MutableList<Any?>,
            queue = queue,
            dirtyTarget = effectiveDirtyTarget,
        )

        is MutableSet<*> -> MongoTrackedMutableSet(
            path = path,
            initialValue = value as MutableSet<Any?>,
            queue = queue,
            dirtyTarget = effectiveDirtyTarget,
        )

        is Deque<*> -> MongoTrackedMutableList(
            path = path,
            initialValue = value.toMutableList(),
            queue = queue,
            dirtyTarget = effectiveDirtyTarget,
        )

        else -> value
    }
}

private fun writeBoundaryFor(path: MongoPath, value: Any?): MongoDirtyTarget? {
    if (path.dataDepth() < DEFAULT_WRITE_BOUNDARY_DEPTH || !canHaveNestedMutation(value)) {
        return null
    }
    return MongoDirtyTarget(path, value)
}

private fun canHaveNestedMutation(value: Any?): Boolean {
    return value is MongoDirtyTargetAware ||
            value is MongoPersistentValue ||
            value is MutableMap<*, *> ||
            value is MutableList<*> ||
            value is MutableSet<*> ||
            value is Deque<*>
}

class MongoTrackedValue<T>(
    private val path: MongoPath,
    initialValue: T,
    private val queue: MongoChangeQueue,
    private val dirtyTarget: () -> MongoDirtyTarget? = { null },
    private val leaseProvider: () -> DataLease? = { null },
) {
    private var value = initialValue

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = value

    operator fun setValue(thisRef: Any?, property: KProperty<*>, newValue: T) {
        if (value == newValue) return
        leaseProvider()?.ensureActive()
        value = newValue
        queue.enqueueSet(path, newValue, dirtyTarget())
    }
}

fun <T> mongoTrackedValue(
    path: MongoPath,
    initialValue: T,
    queue: MongoChangeQueue,
    dirtyTarget: MongoDirtyTarget? = null,
): MongoTrackedValue<T> {
    return MongoTrackedValue(path, initialValue, queue, dirtyTarget = { dirtyTarget })
}

fun <T> mongoTrackedValue(
    path: MongoPath,
    initialValue: T,
    queue: MongoChangeQueue,
    dirtyTarget: () -> MongoDirtyTarget?,
    leaseProvider: () -> DataLease? = { null },
): MongoTrackedValue<T> {
    return MongoTrackedValue(path, initialValue, queue, dirtyTarget, leaseProvider)
}

abstract class MongoTrackedObjectSupport(
    private val queue: MongoChangeQueue,
) : MongoPersistentValue, MongoDirtyTargetAware, DataLeaseAware {
    private var dirtyTarget: MongoDirtyTarget? = null
    private var lease: DataLease? = null
    private val dirtyTargetChildren: MutableList<MongoDirtyTargetAware> = mutableListOf()
    private val leaseChildren: MutableList<DataLeaseAware> = mutableListOf()

    override fun bindDirtyTarget(dirtyTarget: MongoDirtyTarget?) {
        this.dirtyTarget = dirtyTarget
        dirtyTargetChildren.forEach { it.bindDirtyTarget(dirtyTarget) }
    }

    override fun bindLease(lease: DataLease) {
        this.lease = lease
        leaseChildren.forEach { it.bindLease(lease) }
    }

    protected fun markSet(path: MongoPath, value: Any?) {
        lease?.ensureActive()
        queue.enqueueSet(path, value, dirtyTarget)
    }

    protected fun ensureActive() {
        lease?.ensureActive()
    }

    protected fun currentDirtyTarget(): MongoDirtyTarget? = dirtyTarget

    /**
     * Registers a generated nested wrapper so unload leases and dirty boundaries propagate through the object graph.
     */
    protected fun <T> trackChild(child: T): T {
        if (child is MongoDirtyTargetAware) {
            dirtyTargetChildren += child
            child.bindDirtyTarget(dirtyTarget)
        }
        if (child is DataLeaseAware) {
            leaseChildren += child
            lease?.let(child::bindLease)
        }
        return child
    }
}
