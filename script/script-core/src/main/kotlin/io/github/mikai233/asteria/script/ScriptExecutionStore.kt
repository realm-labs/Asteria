package io.github.mikai233.asteria.script

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

class InMemoryScriptExecutionStore : ScriptExecutionStore {
    private val mutex = Mutex()
    private val states: MutableMap<ScriptExecutionKey, ScriptExecutionState> = linkedMapOf()

    override suspend fun tryStart(key: ScriptExecutionKey): ScriptExecutionDecision {
        return mutex.withLock {
            when (val state = states[key]) {
                null -> {
                    states[key] = ScriptExecutionState.Running
                    ScriptExecutionDecision.Started
                }

                ScriptExecutionState.Running -> ScriptExecutionDecision.AlreadyRunning
                is ScriptExecutionState.Completed -> ScriptExecutionDecision.AlreadyCompleted(state.result)
            }
        }
    }

    override suspend fun complete(key: ScriptExecutionKey, result: ScriptExecutionResult) {
        mutex.withLock {
            states[key] = ScriptExecutionState.Completed(result)
        }
    }
}

private sealed interface ScriptExecutionState {
    data object Running : ScriptExecutionState

    data class Completed(val result: ScriptExecutionResult) : ScriptExecutionState
}
