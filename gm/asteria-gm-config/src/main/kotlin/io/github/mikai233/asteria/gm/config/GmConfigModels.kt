package io.github.mikai233.asteria.gm.config

import io.github.mikai233.asteria.config.ConfigRevision
import io.github.mikai233.asteria.config.ConfigTableName

/**
 * Metadata of the currently loaded config snapshot.
 */
data class GmConfigMetadata(
    val revision: ConfigRevision,
    val tableCount: Int,
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
