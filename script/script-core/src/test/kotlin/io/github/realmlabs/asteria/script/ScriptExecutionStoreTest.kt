package io.github.realmlabs.asteria.script

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

class ScriptExecutionStoreTest {
    @Test
    fun runningExecutionRejectsDuplicateStart() = runBlocking {
        val store = InMemoryScriptExecutionStore()
        val key = executionKey("running")

        assertEquals(ScriptExecutionDecision.Started, store.tryStart(key))
        assertEquals(ScriptExecutionDecision.AlreadyRunning, store.tryStart(key))
    }

    @Test
    fun completedExecutionIsReplayedBeforeTtlExpires() = runBlocking {
        val now = 0L
        val store = InMemoryScriptExecutionStore(
            completedResultTtl = 10.milliseconds,
            currentTimeMillis = { now },
        )
        val key = executionKey("completed")
        val result = executionResult("completed")

        assertEquals(ScriptExecutionDecision.Started, store.tryStart(key))
        store.complete(key, result)

        val replay = store.tryStart(key)
        assertIs<ScriptExecutionDecision.AlreadyCompleted>(replay)
        assertEquals(result, replay.result)
    }

    @Test
    fun completedExecutionExpiresAfterTtl() = runBlocking {
        var now = 0L
        val store = InMemoryScriptExecutionStore(
            completedResultTtl = 10.milliseconds,
            currentTimeMillis = { now },
        )
        val key = executionKey("expired")

        assertEquals(ScriptExecutionDecision.Started, store.tryStart(key))
        store.complete(key, executionResult("expired"))
        now = 11L

        assertEquals(ScriptExecutionDecision.Started, store.tryStart(key))
    }

    @Test
    fun completedExecutionStoreEvictsOldestResultWhenCapacityIsExceeded() = runBlocking {
        var now = 0L
        val store = InMemoryScriptExecutionStore(
            completedResultTtl = 1.days,
            maxCompletedEntries = 1,
            currentTimeMillis = { now },
        )
        val first = executionKey("first")
        val second = executionKey("second")

        assertEquals(ScriptExecutionDecision.Started, store.tryStart(first))
        store.complete(first, executionResult("first"))
        now = 1L
        assertEquals(ScriptExecutionDecision.Started, store.tryStart(second))
        store.complete(second, executionResult("second"))

        assertEquals(ScriptExecutionDecision.Started, store.tryStart(first))
        val replay = store.tryStart(second)
        assertIs<ScriptExecutionDecision.AlreadyCompleted>(replay)
        assertEquals("second", replay.result.executionId)
    }

    private fun executionKey(id: String): ScriptExecutionKey {
        return ScriptExecutionKey(
            executionId = id,
            scope = ScriptExecutionScope.Actor,
            target = "target-$id",
            actorPath = "/user/$id",
        )
    }

    private fun executionResult(id: String): ScriptExecutionResult {
        return ScriptExecutionResult(
            executionId = id,
            success = true,
            target = "target-$id",
        )
    }
}
