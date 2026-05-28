package io.github.realmlabs.asteria.gm.config

import io.github.realmlabs.asteria.config.*

/**
 * [GmConfigInspector] backed by the currently loaded [ConfigSnapshot].
 */
class SnapshotGmConfigInspector(
    private val configService: ConfigService,
    private val projector: ConfigRowProjector = ReflectionConfigRowProjector(),
    private val reloadMonitor: ConfigReloadMonitor? = null,
) : GmConfigInspector {
    private val cache = GmConfigViewCache(projector)

    override suspend fun metadata(): GmConfigMetadata {
        val snapshot = configService.current()
        return GmConfigMetadata(
            revision = snapshot.revision,
            tableCount = snapshot.tables().size,
        )
    }

    override suspend fun reloadStatus(): GmConfigReloadStatus {
        val status = reloadMonitor?.status(configService.currentOrNull())
            ?: ConfigReloadStatus(
                currentRevision = configService.currentOrNull()?.revision,
                lastSuccess = null,
                lastFailure = null,
                recent = emptyList(),
            )
        return status.toGm()
    }

    override suspend fun reloadHistory(limit: Int): List<GmConfigReloadRecord> {
        require(limit > 0) { "GM config reload history limit must be positive" }
        return reloadMonitor?.history(limit).orEmpty().map { it.toGm() }
    }

    override suspend fun reloadNow(): GmConfigReloadRecord {
        return configService.reload().toGm()
    }

    override suspend fun listTables(): List<GmConfigTableSummary> {
        return configService.current()
            .tables()
            .map {
                GmConfigTableSummary(
                    name = it.name.value,
                    keyType = it.keyTypeName(),
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
        val table =
            snapshot.table(name) ?: error("config table $name not found in revision ${snapshot.revision.version}")
        return cache.view(snapshot.revision, table)
    }
}

private fun ConfigReloadStatus.toGm(): GmConfigReloadStatus {
    return GmConfigReloadStatus(
        currentRevision = currentRevision,
        lastSuccess = lastSuccess?.toGm(),
        lastFailure = lastFailure?.toGm(),
        recent = recent.map { it.toGm() },
    )
}

private fun ConfigReloadRecord.toGm(): GmConfigReloadRecord {
    return when (this) {
        is ConfigReloadRecord.Success -> GmConfigReloadRecord(
            id = id,
            status = GmConfigReloadRecordStatus.Success,
            occurredAt = occurredAt,
            previousRevision = previousRevision,
            currentRevision = currentRevision,
            addedTables = diff.addedTables.map { it.toGm() },
            removedTables = diff.removedTables.map { it.toGm() },
            changedTables = diff.changedTables.map { it.toGm() },
        )

        is ConfigReloadRecord.Failure -> GmConfigReloadRecord(
            id = id,
            status = GmConfigReloadRecordStatus.Failure,
            occurredAt = occurredAt,
            signalReason = signal?.reason,
            signalSource = signal?.source,
            message = message,
        )
    }
}

private fun ConfigReloadResult.toGm(): GmConfigReloadRecord {
    val diff = ConfigSnapshotDiff.between(previous, current)
    return GmConfigReloadRecord(
        id = 0,
        status = GmConfigReloadRecordStatus.Success,
        occurredAt = java.time.Instant.now(),
        previousRevision = previous?.revision,
        currentRevision = current.revision,
        addedTables = diff.addedTables.map { it.toGm() },
        removedTables = diff.removedTables.map { it.toGm() },
        changedTables = diff.changedTables.map { it.toGm() },
    )
}

private fun ConfigTableChange.toGm(): GmConfigChangedTable {
    return GmConfigChangedTable(
        name = name.value,
        keyType = keyType ?: "none",
        rowType = rowType,
        previousSize = previousSize,
        currentSize = currentSize,
        keyChange = keyChange?.toGm(),
    )
}

private fun ConfigTableKeyChange.toGm(): GmConfigChangedKeys {
    return GmConfigChangedKeys(
        keyType = keyType,
        addedKeys = addedKeys.map { it.toString() },
        removedKeys = removedKeys.map { it.toString() },
        updatedKeys = updatedKeys.map { it.toString() },
        changedKeys = changedKeys.map { it.toString() },
    )
}

private class GmConfigViewCache(
    private val projector: ConfigRowProjector,
) {
    private var revision: ConfigRevision? = null
    private val tables: MutableMap<ConfigTableName, GmConfigTableView> = linkedMapOf()

    fun view(
        revision: ConfigRevision,
        table: ConfigTable<*>,
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

private fun ConfigTable<*>.toView(projector: ConfigRowProjector): GmConfigTableView {
    val rows = when (this) {
        is KeyedConfigTable<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            val typed = this as KeyedConfigTable<Any, Any>
            typed.keys.mapNotNull { id ->
                typed[id]?.let { row ->
                    GmConfigRow(
                        id = id.toString(),
                        values = projector.project(row),
                    )
                }
            }
        }

        else -> all().mapIndexed { index, row ->
            GmConfigRow(
                id = index.toString(),
                values = projector.project(row),
            )
        }
    }
    return GmConfigTableView(
        descriptor = GmConfigTableDescriptor(
            name = name.value,
            keyType = keyTypeName(),
            rowType = rowType.qualifiedName ?: rowType.simpleName ?: "unknown",
            size = size,
            fields = projector.describe(rowType),
        ),
        rows = rows,
    )
}

private fun ConfigTable<*>.keyTypeName(): String {
    val keyType = (this as? KeyedConfigTable<*, *>)?.keyType ?: return "none"
    return keyType.qualifiedName ?: keyType.simpleName ?: "unknown"
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
