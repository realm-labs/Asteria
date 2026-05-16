package io.github.realmlabs.asteria.persistence.mongodb.scanned

import io.github.realmlabs.asteria.persistence.AutoFlushMemData
import io.github.realmlabs.asteria.persistence.mongodb.write.MongoScanFlushPolicy
import io.github.realmlabs.asteria.persistence.mongodb.write.MongoScanFlushProgress

/**
 * Common contract for scanned Mongo row tables.
 */
interface MongoScannedTable {
    /**
     * Performs bounded scan/flush work for one scheduler tick.
     */
    suspend fun tick(policy: MongoScanFlushPolicy): MongoScanFlushProgress

    /**
     * Scans loaded rows and flushes all currently known dirty rows.
     *
     * Returning false means at least one row still has pending writes and should remain loaded.
     */
    suspend fun drain(): Boolean
}

/**
 * Convenience mix-in for mem data that owns one or more scan-based Mongo tables.
 *
 * [io.github.realmlabs.asteria.persistence.DataManager] calls [AutoFlushMemData.tick], [AutoFlushMemData.flush], and
 * [AutoFlushMemData.drain], so business data can implement this interface to make scan + flush scheduling part of the
 * normal data lifecycle.
 */
interface MongoScannedTableData : AutoFlushMemData {
    /**
     * Shared scheduling policy applied to all [scannedTables].
     */
    val scanFlushPolicy: MongoScanFlushPolicy

    /**
     * Tables owned by this data unit and included in tick/flush/drain lifecycle calls.
     */
    val scannedTables: Iterable<MongoScannedTable>

    override suspend fun tick() {
        scannedTables.forEach { table -> table.tick(scanFlushPolicy) }
    }

    override suspend fun flush(): Boolean {
        var success = true
        scannedTables.forEach { table ->
            val progress = table.tick(scanFlushPolicy)
            success = progress.flush.failedRows == 0 && success
        }
        return success
    }

    override suspend fun drain(): Boolean {
        var success = true
        scannedTables.forEach { table ->
            success = table.drain() && success
        }
        return success
    }
}
