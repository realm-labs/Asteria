package io.github.mikai233.asteria.gm.config

import io.github.mikai233.asteria.config.ConfigRevision
import io.github.mikai233.asteria.config.ConfigService
import io.github.mikai233.asteria.config.ConfigSnapshot
import io.github.mikai233.asteria.config.ConfigTable
import io.github.mikai233.asteria.config.ConfigTableName

/**
 * [GmConfigInspector] backed by the currently loaded [ConfigSnapshot].
 */
class SnapshotGmConfigInspector(
    private val configService: ConfigService,
    private val projector: ConfigRowProjector = ReflectionConfigRowProjector(),
) : GmConfigInspector {
    private val cache = GmConfigViewCache(projector)

    override suspend fun metadata(): GmConfigMetadata {
        val snapshot = configService.current()
        return GmConfigMetadata(
            revision = snapshot.revision,
            tableCount = snapshot.tables().size,
        )
    }

    override suspend fun listTables(): List<GmConfigTableSummary> {
        return configService.current()
            .tables()
            .map {
                GmConfigTableSummary(
                    name = it.name.value,
                    keyType = it.keyType.qualifiedName ?: it.keyType.simpleName ?: "unknown",
                    rowType = it.rowType.qualifiedName ?: it.rowType.simpleName ?: "unknown",
                    size = it.size,
                )
            }
            .sortedBy { it.name }
    }

    override suspend fun describeTable(name: ConfigTableName): GmConfigTableDescriptor {
        return view(name).descriptor
    }

    override suspend fun queryRows(
        name: ConfigTableName,
        query: GmConfigRowQuery,
    ): GmConfigRowPage {
        val view = view(name)
        val filtered = view.rows.asSequence()
            .filter { row -> query.keyword == null || row.matchesKeyword(query.keyword) }
            .filter { row -> query.filters.all { row.matchesFilter(it) } }
            .toList()
        return GmConfigRowPage(
            table = name.value,
            rows = filtered.drop(query.offset).take(query.limit),
            offset = query.offset,
            limit = query.limit,
            total = filtered.size.toLong(),
        )
    }

    override suspend fun findRow(
        name: ConfigTableName,
        id: String,
    ): GmConfigRow? {
        return view(name).rowsById[id]
    }

    private fun view(name: ConfigTableName): GmConfigTableView {
        val snapshot = configService.current()
        val table = snapshot.table(name) ?: error("config table $name not found in revision ${snapshot.revision.version}")
        return cache.view(snapshot.revision, table)
    }
}

private class GmConfigViewCache(
    private val projector: ConfigRowProjector,
) {
    private var revision: ConfigRevision? = null
    private val tables: MutableMap<ConfigTableName, GmConfigTableView> = linkedMapOf()

    fun view(
        revision: ConfigRevision,
        table: ConfigTable<*, *>,
    ): GmConfigTableView {
        if (this.revision != revision) {
            this.revision = revision
            tables.clear()
        }
        return tables.getOrPut(table.name) { table.toView(projector) }
    }
}

private data class GmConfigTableView(
    val descriptor: GmConfigTableDescriptor,
    val rows: List<GmConfigRow>,
) {
    val rowsById: Map<String, GmConfigRow> = rows.associateBy { it.id }
}

private fun ConfigTable<*, *>.toView(projector: ConfigRowProjector): GmConfigTableView {
    @Suppress("UNCHECKED_CAST")
    val typed = this as ConfigTable<Any, Any>
    val rows = typed.ids.mapNotNull { id ->
        typed[id]?.let { row ->
            GmConfigRow(
                id = id.toString(),
                values = projector.project(row),
            )
        }
    }
    return GmConfigTableView(
        descriptor = GmConfigTableDescriptor(
            name = name.value,
            keyType = keyType.qualifiedName ?: keyType.simpleName ?: "unknown",
            rowType = rowType.qualifiedName ?: rowType.simpleName ?: "unknown",
            size = size,
            fields = projector.describe(rowType),
        ),
        rows = rows,
    )
}

private fun GmConfigRow.matchesKeyword(keyword: String): Boolean {
    val normalized = keyword.lowercase()
    return id.lowercase().contains(normalized) || values.values.any { value ->
        value.flattenText().any { it.lowercase().contains(normalized) }
    }
}

private fun GmConfigRow.matchesFilter(filter: GmConfigRowFilter): Boolean {
    val value = values[filter.field] ?: return false
    return when (filter.op) {
        GmConfigFilterOperator.Eq -> value.flattenText().any { it == filter.value }
        GmConfigFilterOperator.Contains -> value.flattenText().any { it.contains(filter.value, ignoreCase = true) }
    }
}

private fun Any?.flattenText(): List<String> {
    return when (this) {
        null -> emptyList()
        is Map<*, *> -> values.flatMap { it.flattenText() }
        is Iterable<*> -> flatMap { it.flattenText() }
        is Array<*> -> flatMap { it.flattenText() }
        else -> listOf(toString())
    }
}
