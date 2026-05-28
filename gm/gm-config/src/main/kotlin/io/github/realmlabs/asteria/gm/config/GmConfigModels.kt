package io.github.realmlabs.asteria.gm.config

import io.github.realmlabs.asteria.config.ConfigRevision
import io.github.realmlabs.asteria.config.ConfigTableName
import java.time.Instant

/**
 * Metadata of the currently loaded config snapshot.
 */
data class GmConfigMetadata(
    val revision: ConfigRevision,
    val tableCount: Int,
)

/**
 * Reload diagnostics shown in GM.
 */
data class GmConfigReloadStatus(
    val currentRevision: ConfigRevision?,
    val lastSuccess: GmConfigReloadRecord?,
    val lastFailure: GmConfigReloadRecord?,
    val recent: List<GmConfigReloadRecord>,
)

/**
 * One GM-facing reload record.
 */
data class GmConfigReloadRecord(
    val id: Long,
    val status: GmConfigReloadRecordStatus,
    val occurredAt: Instant,
    val previousRevision: ConfigRevision? = null,
    val currentRevision: ConfigRevision? = null,
    val addedTables: List<GmConfigChangedTable> = emptyList(),
    val removedTables: List<GmConfigChangedTable> = emptyList(),
    val changedTables: List<GmConfigChangedTable> = emptyList(),
    val signalReason: String? = null,
    val signalSource: String? = null,
    val message: String? = null,
)

enum class GmConfigReloadRecordStatus {
    Success,
    Failure,
}

/**
 * Table-level change summary.
 */
data class GmConfigChangedTable(
    val name: String,
    val keyType: String,
    val rowType: String,
    val previousSize: Int?,
    val currentSize: Int?,
    val keyChange: GmConfigChangedKeys? = null,
)

/**
 * Key-level change summary for a keyed config table.
 */
data class GmConfigChangedKeys(
    val keyType: String,
    val addedKeys: List<String> = emptyList(),
    val removedKeys: List<String> = emptyList(),
    val updatedKeys: List<String> = emptyList(),
    val changedKeys: List<String> = emptyList(),
)

/**
 * One table shown in the GM config browser.
 */
data class GmConfigTableSummary(
    val name: String,
    val keyType: String,
    val rowType: String,
    val size: Int,
)

/**
 * Field metadata used by a GM frontend to render table columns and filters.
 */
data class GmConfigFieldDescriptor(
    val name: String,
    val type: String,
    val nullable: Boolean = true,
    val indexed: Boolean = false,
    val sensitive: Boolean = false,
) {
    init {
        require(name.isNotBlank()) { "GM config field name must not be blank" }
        require(type.isNotBlank()) { "GM config field type must not be blank" }
    }
}

/**
 * Table schema and row count for one config table.
 */
data class GmConfigTableDescriptor(
    val name: String,
    val keyType: String,
    val rowType: String,
    val size: Int,
    val fields: List<GmConfigFieldDescriptor>,
)

/**
 * One projected config row.
 */
data class GmConfigRow(
    val id: String,
    val values: Map<String, Any?>,
)

/**
 * Filter operator supported by first-version config browsing.
 */
enum class GmConfigFilterOperator {
    Eq,
    Contains,
}

/**
 * One field filter in a config row query.
 */
data class GmConfigRowFilter(
    val field: String,
    val op: GmConfigFilterOperator,
    val value: String,
) {
    init {
        require(field.isNotBlank()) { "GM config filter field must not be blank" }
    }
}

/**
 * Query accepted by the config browser.
 */
data class GmConfigRowQuery(
    val keyword: String? = null,
    val filters: List<GmConfigRowFilter> = emptyList(),
    val offset: Int = 0,
    val limit: Int = 100,
) {
    init {
        keyword?.let { require(it.isNotBlank()) { "GM config query keyword must not be blank" } }
        require(offset >= 0) { "GM config query offset must not be negative" }
        require(limit > 0) { "GM config query limit must be positive" }
    }
}

/**
 * Paged config row result.
 */
data class GmConfigRowPage(
    val table: String,
    val rows: List<GmConfigRow>,
    val offset: Int,
    val limit: Int,
    val total: Long,
) {
    val nextOffset: Int? = if (offset + rows.size < total) offset + rows.size else null
}

/**
 * Runtime-neutral config browser.
 */
interface GmConfigInspector {
    suspend fun metadata(): GmConfigMetadata

    suspend fun reloadStatus(): GmConfigReloadStatus

    suspend fun reloadHistory(limit: Int = 20): List<GmConfigReloadRecord>

    suspend fun reloadNow(): GmConfigReloadRecord

    suspend fun listTables(): List<GmConfigTableSummary>

    suspend fun describeTable(name: ConfigTableName): GmConfigTableDescriptor

    suspend fun queryRows(
        name: ConfigTableName,
        query: GmConfigRowQuery = GmConfigRowQuery(),
    ): GmConfigRowPage

    suspend fun findRow(
        name: ConfigTableName,
        id: String,
    ): GmConfigRow?
}
