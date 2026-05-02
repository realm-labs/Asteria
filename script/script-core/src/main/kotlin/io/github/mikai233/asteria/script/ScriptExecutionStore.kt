package io.github.mikai233.asteria.script

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class ScriptExecutionKey(
    val executionId: String,
    val scope: ScriptExecutionScope,
    val target: String,
    val nodeAddress: String? = null,
    val actorPath: String? = null,
) {
    init {
        require(executionId.isNotBlank()) { "script execution id must not be blank" }
        require(target.isNotBlank()) { "script execution target must not be blank" }
    }
}

sealed interface ScriptExecutionDecision {
    data object Started : ScriptExecutionDecision

    data object AlreadyRunning : ScriptExecutionDecision

    data class AlreadyCompleted(val result: ScriptExecutionResult) : ScriptExecutionDecision
}

interface ScriptExecutionStore {
    suspend fun tryStart(key: ScriptExecutionKey): ScriptExecutionDecision

    suspend fun complete(key: ScriptExecutionKey, result: ScriptExecutionResult)
}

/**
 * In-memory idempotency store used by [ScriptRunner].
 *
 * The store only protects a single node/actor execution key from duplicate execution and replaying
 * recent completed results. It is intentionally not the durable job repository; long running GM jobs
 * should still be tracked by `ScriptJobRepository`.
 */
class InMemoryScriptExecutionStore(
    private val completedResultTtl: Duration = 30.minutes,
    private val maxCompletedEntries: Int = 10_000,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : ScriptExecutionStore {
    private val states: ConcurrentHashMap<ScriptExecutionKey, ScriptExecutionState> = ConcurrentHashMap()
    private val completedEntries = AtomicInteger()
    private val sequence = AtomicLong()
    private val completions = AtomicLong()

    init {
        require(completedResultTtl > Duration.ZERO) { "script completed result ttl must be positive" }
        require(maxCompletedEntries > 0) { "script completed result capacity must be positive" }
    }

    override suspend fun tryStart(key: ScriptExecutionKey): ScriptExecutionDecision {
        while (true) {
            val now = currentTimeMillis()
            when (val state = states[key]) {
                null -> {
                    if (states.putIfAbsent(key, ScriptExecutionState.Running) == null) {
                        return ScriptExecutionDecision.Started
                    }
                }

                ScriptExecutionState.Running -> return ScriptExecutionDecision.AlreadyRunning
                is ScriptExecutionState.Completed -> {
                    if (!state.isExpired(now)) {
                        return ScriptExecutionDecision.AlreadyCompleted(state.result)
                    }
                    removeCompleted(key, state)
                }
            }
        }
    }

    override suspend fun complete(key: ScriptExecutionKey, result: ScriptExecutionResult) {
        val completed = ScriptExecutionState.Completed(
            result = result,
            completedAtMillis = currentTimeMillis(),
            sequence = sequence.incrementAndGet(),
        )
        val previous = states.put(key, completed)
        if (previous !is ScriptExecutionState.Completed) {
            completedEntries.incrementAndGet()
        }
        val completedCount = completions.incrementAndGet()
        if (completedEntries.get() > maxCompletedEntries || completedCount % CLEANUP_INTERVAL == 0L) {
            cleanup()
        }
    }

    private fun cleanup() {
        val now = currentTimeMillis()
        states.forEach { (key, state) ->
            if (state is ScriptExecutionState.Completed && state.isExpired(now)) {
                removeCompleted(key, state)
            }
        }
        val overflow = completedEntries.get() - maxCompletedEntries
        if (overflow <= 0) {
            return
        }
        states.asSequence()
            .mapNotNull { (key, state) ->
                if (state is ScriptExecutionState.Completed) key to state else null
            }
            .sortedWith(compareBy<Pair<ScriptExecutionKey, ScriptExecutionState.Completed>> { it.second.completedAtMillis }
                .thenBy { it.second.sequence })
            .take(overflow)
            .forEach { (key, state) -> removeCompleted(key, state) }
    }

    private fun removeCompleted(key: ScriptExecutionKey, state: ScriptExecutionState.Completed) {
        if (states.remove(key, state)) {
            completedEntries.decrementAndGet()
        }
    }

    private fun ScriptExecutionState.Completed.isExpired(nowMillis: Long): Boolean {
        return nowMillis - completedAtMillis >= completedResultTtl.inWholeMilliseconds
    }

    private companion object {
        const val CLEANUP_INTERVAL: Long = 64
    }
}

private sealed interface ScriptExecutionState {
    data object Running : ScriptExecutionState

    data class Completed(
        val result: ScriptExecutionResult,
        val completedAtMillis: Long,
        val sequence: Long,
    ) : ScriptExecutionState
}
