package io.github.realmlabs.asteria.persistence.mongodb

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class MongoScannedTableDataTest {
    @Test
    fun `flush advances one scan flush tick and drain uses full drain path`(): Unit = runBlocking {
        val table = RecordingScannedTable()
        val data = TestScannedTableData(table)

        assertTrue(data.flush())
        assertEquals(1, table.ticks)
        assertEquals(0, table.drains)

        assertTrue(data.drain())
        assertEquals(1, table.ticks)
        assertEquals(1, table.drains)
    }

    @Test
    fun `flush reports failure when tick leaves failed rows`(): Unit = runBlocking {
        val table = RecordingScannedTable(
            tickResult = MongoScanFlushProgress(
                scan = MongoScanProgress(scannedRows = 1, dirtyRows = 1, changedFields = 1),
                flush = MongoFlushProgress(attemptedRows = 1, flushedRows = 0, failedRows = 1),
            ),
        )
        val data = TestScannedTableData(table)

        assertFalse(data.flush())
    }
}

private class TestScannedTableData(
    table: MongoScannedTable,
) : MongoScannedTableData {
    override val scanFlushPolicy: MongoScanFlushPolicy = MongoScanFlushPolicy(
        scanBudget = MongoFlushBudget(maxRows = 1, maxDuration = 1.milliseconds),
        flushBudget = MongoFlushBudget(maxRows = 1, maxDuration = 1.milliseconds),
    )
    override val scannedTables: Iterable<MongoScannedTable> = listOf(table)

    override suspend fun load() = Unit
}

private class RecordingScannedTable(
    private val tickResult: MongoScanFlushProgress = MongoScanFlushProgress(
        scan = MongoScanProgress(scannedRows = 0, dirtyRows = 0, changedFields = 0),
        flush = MongoFlushProgress(attemptedRows = 0, flushedRows = 0, failedRows = 0),
    ),
    private val drainResult: Boolean = true,
) : MongoScannedTable {
    var ticks: Int = 0
    var drains: Int = 0

    override suspend fun tick(policy: MongoScanFlushPolicy): MongoScanFlushProgress {
        ticks += 1
        return tickResult
    }

    override suspend fun drain(): Boolean {
        drains += 1
        return drainResult
    }
}
