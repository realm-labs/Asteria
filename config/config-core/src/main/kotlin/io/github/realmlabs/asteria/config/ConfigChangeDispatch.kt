package io.github.realmlabs.asteria.config

import io.github.realmlabs.asteria.config.ConfigChangeFailureHandler.Companion.IGNORE
import io.github.realmlabs.asteria.config.ConfigChangeFailureHandler.Companion.THROW


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
    private val executor: ConfigChangeExecutor<R> = ConfigChangeExecutor.DIRECT,
    private val failureHandler: ConfigChangeFailureHandler<R> = ConfigChangeFailureHandler.THROW,
) {
    private val handlers: List<ConfigChangeHandler<R>> = handlers.toList()

    /**
     * Runs handlers whose [ConfigChangeHandler.watchedTables] intersect with [event.changedTables].
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
     * The tracker is updated after all matching handler tasks are submitted to [executor].
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
     * The tracker is updated after all handler tasks are submitted to [executor].
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
        for (handler in selectedHandlers) {
            val handlerName = handler::class.qualifiedName ?: handler::class.toString()
            executor.execute(
                receiver,
                ConfigChangeTask(
                    handler = handlerName,
                    revision = snapshot.revision,
                ) {
                    try {
                        handler.handle(receiver, snapshot)
                    } catch (error: Throwable) {
                        failureHandler.handle(receiver, ConfigChangeFailure(snapshot.revision, handlerName, error))
                    }
                },
            )
        }
    }
}

/**
 * Executes a config change handler task.
 *
 * Actor runtimes can adapt this to their mailbox, for example:
 *
 * `ConfigChangeExecutor<MyActor> { actor, task -> actor.execute("config-change:${task.handler}", task::run) }`
 */
fun interface ConfigChangeExecutor<in R : Any> {
    fun execute(
        receiver: R,
        task: ConfigChangeTask,
    )

    companion object {
        val DIRECT: ConfigChangeExecutor<Any> = ConfigChangeExecutor { _, task -> task.run() }
    }
}

class ConfigChangeTask internal constructor(
    val handler: String,
    val revision: ConfigRevision,
    private val block: () -> Unit,
) {
    fun run() {
        block()
    }
}

/**
 * Failure raised while applying one config change handler task.
 *
 * The dispatcher catches handler exceptions inside the submitted task and forwards this value to the configured
 * [ConfigChangeFailureHandler]. Executor failures that happen before task submission are not wrapped here.
 */
data class ConfigChangeFailure(
    val revision: ConfigRevision,
    val handler: String,
    val cause: Throwable,
)

/**
 * Handles exceptions thrown by [ConfigChangeHandler.handle].
 *
 * The default [THROW] policy rethrows the original cause from the handler task. Use [IGNORE] only when the receiver can
 * tolerate stale derived state or when failures are reported through another channel.
 */
fun interface ConfigChangeFailureHandler<in R : Any> {
    fun handle(
        receiver: R,
        failure: ConfigChangeFailure,
    )

    companion object {
        val THROW: ConfigChangeFailureHandler<Any> = ConfigChangeFailureHandler { _, failure -> throw failure.cause }
        val IGNORE: ConfigChangeFailureHandler<Any> = ConfigChangeFailureHandler { _, _ -> }
    }
}

/**
 * Stores the last config revision handled by a receiver.
 *
 * Framework code only needs this small contract; projects can back it with actor state, MongoDB, Redis, or an in-memory
 * field depending on the receiver lifecycle.
 */
interface ConfigRevisionTracker {
    /**
     * Last config revision that was fully submitted for this receiver, or `null` before the first dispatch.
     */
    fun currentRevision(): String?

    /**
     * Records a revision after dispatch submission.
     *
     * Implementations that persist this value should update it atomically with receiver state when they need stronger
     * exactly-once semantics than [ConfigChangeDispatcher.dispatchIfNew] can provide by itself.
     */
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
