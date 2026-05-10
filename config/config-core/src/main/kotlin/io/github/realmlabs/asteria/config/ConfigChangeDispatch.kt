package io.github.realmlabs.asteria.config

/**
 * Handles config-derived state for one receiver type.
 *
 * A handler declares the tables it depends on through [watchedTables]. Dispatching a [ConfigChangedEvent] invokes only
 * handlers whose watched tables intersect with [ConfigChangedEvent.changedTables]. Dispatching a [ConfigSnapshot]
 * invokes every handler.
 *
 * Handlers should synchronize receiver state from the supplied snapshot and remain idempotent.
 */
interface ConfigChangeHandler<R : Any> {
    /**
     * Table names that make [handle] relevant for a published reload event.
     */
    val watchedTables: Set<ConfigTableName>

    /**
     * Synchronizes [receiver] from the current config [snapshot].
     */
    fun handle(
        receiver: R,
        snapshot: ConfigSnapshot,
    )
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
        dispatchToHandlers(
            receiver = receiver,
            snapshot = event.current,
            selectedHandlers = handlers.filter { handler ->
                handler.watchedTables.any(event.changedTables::contains)
            },
        )
    }

    /**
     * Runs every handler against [snapshot], regardless of watched table names.
     */
    fun dispatch(
        receiver: R,
        snapshot: ConfigSnapshot,
    ) {
        dispatchToHandlers(receiver, snapshot, handlers)
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
     * Dispatches [snapshot] only when [tracker] has not already recorded [ConfigSnapshot.revision].
     *
     * The tracker is updated after all handlers complete successfully.
     */
    fun dispatchIfNew(
        receiver: R,
        snapshot: ConfigSnapshot,
        tracker: ConfigRevisionTracker,
    ): Boolean {
        if (tracker.currentRevision() == snapshot.revision.version) {
            return false
        }
        dispatch(receiver, snapshot)
        tracker.updateRevision(snapshot.revision.version)
        return true
    }

    private fun dispatchToHandlers(
        receiver: R,
        snapshot: ConfigSnapshot,
        selectedHandlers: Iterable<ConfigChangeHandler<R>>,
    ) {
        val failures = mutableListOf<ConfigChangeHandlerFailure>()
        for (handler in selectedHandlers) {
            try {
                handler.handle(receiver, snapshot)
            } catch (error: Throwable) {
                failures += ConfigChangeHandlerFailure(handler::class.qualifiedName ?: handler::class.toString(), error)
            }
        }
        if (failures.isNotEmpty()) {
            throw ConfigChangeDispatchException(snapshot.revision, failures)
        }
    }
}

data class ConfigChangeHandlerFailure(
    val handler: String,
    val cause: Throwable,
)

class ConfigChangeDispatchException(
    val revision: ConfigRevision,
    val failures: List<ConfigChangeHandlerFailure>,
) : IllegalStateException(
    "config change dispatch failed for revision ${revision.version}: " +
            failures.joinToString { "${it.handler}: ${it.cause.message ?: it.cause::class.simpleName}" },
) {
    init {
        require(failures.isNotEmpty()) { "config change dispatch failures must not be empty" }
        failures.forEach { addSuppressed(it.cause) }
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
