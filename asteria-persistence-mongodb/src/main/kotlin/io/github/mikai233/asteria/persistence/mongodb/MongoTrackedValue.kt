package io.github.mikai233.asteria.persistence.mongodb

import java.util.Deque
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
            initialValue = value.toMutableList() as MutableList<Any?>,
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
) {
    private var value = initialValue

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = value

    operator fun setValue(thisRef: Any?, property: KProperty<*>, newValue: T) {
        if (value == newValue) return
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
    return MongoTrackedValue(path, initialValue, queue) { dirtyTarget }
}

fun <T> mongoTrackedValue(
    path: MongoPath,
    initialValue: T,
    queue: MongoChangeQueue,
    dirtyTarget: () -> MongoDirtyTarget?,
): MongoTrackedValue<T> {
    return MongoTrackedValue(path, initialValue, queue, dirtyTarget)
}

abstract class MongoTrackedObjectSupport(
    private val queue: MongoChangeQueue,
) : MongoPersistentValue, MongoDirtyTargetAware {
    private var dirtyTarget: MongoDirtyTarget? = null

    override fun bindDirtyTarget(dirtyTarget: MongoDirtyTarget?) {
        this.dirtyTarget = dirtyTarget
    }

    protected fun markSet(path: MongoPath, value: Any?) {
        queue.enqueueSet(path, value, dirtyTarget)
    }

    protected fun currentDirtyTarget(): MongoDirtyTarget? = dirtyTarget
}
