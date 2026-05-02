package io.github.mikai233.asteria.persistence.mongodb

import io.github.mikai233.asteria.persistence.AutoFlushMemData

/**
 * Common contract for scanned Mongo row tables.
 */
interface MongoScannedTable {
    suspend fun tick(policy: MongoScanFlushPolicy): MongoScanFlushProgress

    suspend fun flushAllScanned(): Boolean
}

/**
 * Convenience mix-in for mem data that owns one or more scan-based Mongo tables.
 *
 * [io.github.mikai233.asteria.persistence.DataManager] already calls [AutoFlushMemData.tick] and [AutoFlushMemData.flush],
 * so business data can implement this interface to make scan + flush scheduling part of the normal data lifecycle.
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
            success = table.flushAllScanned() && success
        }
        return success
    }
}
