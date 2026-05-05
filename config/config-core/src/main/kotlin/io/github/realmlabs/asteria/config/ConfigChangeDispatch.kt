package io.github.realmlabs.asteria.config

/**
 * Handles config-derived state for one receiver type.
 *
 * A handler declares the tables it depends on through [watchedTables]. During a hot reload, [ConfigChangeDispatcher]
 * invokes only handlers whose watched tables intersect with [ConfigChangedEvent.changedTables]. During catch-up, all
 * handlers run because the receiver may have missed any number of revisions while it was offline or unloaded.
 *
 * Handlers should be idempotent against the supplied snapshot. Config reload events can be replayed by business code,
 * and catch-up can run after the same revision was already handled if the caller does not use a revision tracker.
 */
interface ConfigChangeHandler<R : Any> {
    /**
     * Table names that make [handleChange] relevant for a published reload event.
     */
    val watchedTables: Set<ConfigTableName>

    /**
     * Applies an online config change to [receiver].
     */
    fun handleChange(
        receiver: R,
        event: ConfigChangedEvent,
    )

    /**
     * Rebuilds receiver state from the current snapshot after the receiver starts, loads, or becomes active.
     */
    fun catchUp(
        receiver: R,
        snapshot: ConfigSnapshot,
    ) = Unit
}

/**
 * Dispatches config reload events to handlers for one receiver type.
 */
class ConfigChangeDispatcher<R : Any>(
    handlers: Iterable<ConfigChangeHandler<R>> = emptyList(),
) {
    private val handlers: List<ConfigChangeHandler<R>> = handlers.toList()

    /**
     * Runs handlers whose [ConfigChangeHandler.watchedTables] intersect with [event.changedTables].
     *
     * If a handler throws, dispatch stops and the exception is propagated. The caller should update any revision
     * tracker only after this method returns successfully.
     */
    fun dispatch(
        receiver: R,
        event: ConfigChangedEvent,
    ) {
        for (handler in handlers) {
            if (handler.watchedTables.any(event.changedTables::contains)) {
                handler.handleChange(receiver, event)
            }
        }
    }

    /**
     * Runs every handler against [snapshot], regardless of watched table names.
     */
    fun catchUp(
        receiver: R,
        snapshot: ConfigSnapshot,
    ) {
        for (handler in handlers) {
            handler.catchUp(receiver, snapshot)
        }
    }

    /**
     * Dispatches [event] only when [tracker] has not already recorded [ConfigChangedEvent.currentRevision].
     *
     * The tracker is updated after all matching handlers complete successfully.
     */
    fun dispatchIfNew(
        receiver: R,
        event: ConfigChangedEvent,
        tracker: ConfigRevisionTracker,
    ): Boolean {
        if (tracker.currentRevision() == event.currentRevision.version) {
            return false
        }
        dispatch(receiver, event)
        tracker.updateRevision(event.currentRevision.version)
        return true
    }

    /**
     * Runs catch-up only when [tracker] has not already recorded [ConfigSnapshot.revision].
     *
     * The tracker is updated after all handlers complete successfully.
     */
    fun catchUpIfNew(
        receiver: R,
        snapshot: ConfigSnapshot,
        tracker: ConfigRevisionTracker,
    ): Boolean {
        if (tracker.currentRevision() == snapshot.revision.version) {
            return false
        }
        catchUp(receiver, snapshot)
        tracker.updateRevision(snapshot.revision.version)
        return true
    }
}

/**
 * Stores the last config revision handled by a receiver.
 *
 * Framework code only needs this small contract; projects can back it with actor state, MongoDB, Redis, or an in-memory
 * field depending on the receiver lifecycle.
 */
interface ConfigRevisionTracker {
    fun currentRevision(): String?

    fun updateRevision(revision: String)
}

fun configTable(name: String): ConfigTableName = ConfigTableName(name)

fun configTable(ref: ConfigTableRef<*, *>): ConfigTableName = ref.name

fun configTable(ref: RowConfigTableRef<*>): ConfigTableName = ref.name

/**
 * Creates a watched-table set from raw names.
 *
 * Prefer generated table references such as `configTables(GameConfigTables.Items)` when they are available. Raw names
 * are intended for dynamic integrations or code that cannot depend on generated config accessors.
 */
fun configTables(vararg names: String): Set<ConfigTableName> {
    return names.mapTo(linkedSetOf(), ::configTable)
}

fun configTables(vararg refs: ConfigTableRef<*, *>): Set<ConfigTableName> {
    return refs.mapTo(linkedSetOf(), ::configTable)
}

fun configTables(vararg refs: RowConfigTableRef<*>): Set<ConfigTableName> {
    return refs.mapTo(linkedSetOf(), ::configTable)
}
