package io.github.realmlabs.asteria.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigChangeDispatcherTest {
    @Test
    fun `dispatch runs only handlers watching changed tables`() {
        val receiver = TestReceiver()
        val dispatcher = ConfigChangeDispatcher(
            listOf(
                RecordingHandler("items", configTables(Items)),
                RecordingHandler("worlds", configTables("worlds")),
            ),
        )

        dispatcher.dispatch(receiver, changeEvent(changedTables = configTables("items")))

        assertEquals(listOf("items:v2"), receiver.events)
    }

    @Test
    fun `snapshot dispatch runs all handlers and revision tracker gates repeated work`() {
        val receiver = TestReceiver()
        val tracker = TestRevisionTracker()
        val dispatcher = ConfigChangeDispatcher(
            listOf(
                RecordingHandler("items", configTables("items")),
                RecordingHandler("worlds", configTables("worlds")),
            ),
        )
        val snapshot = snapshot("v2")

        assertTrue(dispatcher.dispatchIfNew(receiver, snapshot, tracker))
        assertFalse(dispatcher.dispatchIfNew(receiver, snapshot, tracker))

        assertEquals(listOf("items:v2", "worlds:v2"), receiver.events)
        assertEquals("v2", tracker.currentRevision())
    }

    @Test
    fun `dispatch continues after handler failure and does not update tracker`() {
        val receiver = TestReceiver()
        val tracker = TestRevisionTracker()
        val dispatcher = ConfigChangeDispatcher(
            listOf(
                RecordingHandler("before", configTables("items")),
                FailingHandler(configTables("items")),
                RecordingHandler("after", configTables("items")),
            ),
        )

        val error = assertFailsWith<ConfigChangeDispatchException> {
            dispatcher.dispatchIfNew(receiver, changeEvent(changedTables = configTables("items")), tracker)
        }

        assertEquals(listOf("before:v2", "after:v2"), receiver.events)
        assertEquals(null, tracker.currentRevision())
        assertEquals(1, error.failures.size)
        assertEquals("handler failed", error.failures.single().cause.message)
        assertEquals(1, error.suppressedExceptions.size)
    }

    @Test
    fun `row table refs can require list and singleton shapes`() {
        val rewards = rowConfigTableRef<TestRow>("rewards")
        val global = rowConfigTableRef<TestRow>("global")
        val snapshot = DefaultConfigSnapshot(
            revision = ConfigRevision("v1"),
            tables = listOf(
                listConfigTable(rewards, listOf(TestRow(1), TestRow(2))),
                singleConfigTable(global, TestRow(3)),
            ),
        )

        assertEquals(2, snapshot.requireListTable(rewards).size)
        assertEquals(TestRow(3), snapshot.requireSingleTable(global).get())
    }


    private fun changeEvent(changedTables: Set<ConfigTableName>): ConfigChangedEvent {
        return ConfigChangedEvent(
            previousRevision = ConfigRevision("v1"),
            currentRevision = ConfigRevision("v2"),
            current = snapshot("v2"),
            changedTables = changedTables,
        )
    }

    private fun snapshot(revision: String): ConfigSnapshot {
        return DefaultConfigSnapshot(
            revision = ConfigRevision(revision),
            tables = emptyList(),
        )
    }

    private companion object {
        val Items = configTableRef<Int, TestRow>("items")
    }
}

private data class TestRow(
    val id: Int,
)

private class TestReceiver {
    val events: MutableList<String> = mutableListOf()
}

private class RecordingHandler(
    private val name: String,
    override val watchedTables: Set<ConfigTableName>,
) : ConfigChangeHandler<TestReceiver> {
    override fun handle(
        receiver: TestReceiver,
        snapshot: ConfigSnapshot,
    ) {
        receiver.events += "$name:${snapshot.revision.version}"
    }
}

private class FailingHandler(
    override val watchedTables: Set<ConfigTableName>,
) : ConfigChangeHandler<TestReceiver> {
    override fun handle(
        receiver: TestReceiver,
        snapshot: ConfigSnapshot,
    ) {
        error("handler failed")
    }
}

private class TestRevisionTracker : ConfigRevisionTracker {
    private var revision: String? = null

    override fun currentRevision(): String? = revision

    override fun updateRevision(revision: String) {
        this.revision = revision
    }
}
