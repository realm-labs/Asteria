package io.github.realmlabs.asteria.persistence.mongodb

import io.github.realmlabs.asteria.persistence.AutoFlushMemData

/**
 * Common contract for scanned Mongo row tables.
 */
interface MongoScannedTable {
    suspend fun tick(policy: MongoScanFlushPolicy): MongoScanFlushProgress

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
    val scanFlushPolicy: MongoScanFlushPolicy

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
