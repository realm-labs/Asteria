package io.github.realmlabs.asteria.config

/**
 * Table-level difference between two config snapshots.
 *
 * Diff is intentionally table-grained. Row-level diffing is often expensive for generated config tables and is better
 * implemented by applications that know which tables need fine-grained diagnostics.
 */
data class ConfigSnapshotDiff(
    val previousRevision: ConfigRevision?,
    val currentRevision: ConfigRevision,
    val addedTables: List<ConfigTableChange> = emptyList(),
    val removedTables: List<ConfigTableChange> = emptyList(),
    val changedTables: List<ConfigTableChange> = emptyList(),
) {
    val hasChanges: Boolean
        get() = addedTables.isNotEmpty() || removedTables.isNotEmpty() || changedTables.isNotEmpty()

    companion object {
        fun between(
            previous: ConfigSnapshot?,
            current: ConfigSnapshot,
        ): ConfigSnapshotDiff {
            if (previous == null) {
                return ConfigSnapshotDiff(
                    previousRevision = null,
                    currentRevision = current.revision,
                    addedTables = current.tables().map { it.toChange() }.sortedBy { it.name.value },
                )
            }

            val previousTables = previous.tables().associateBy { it.name }
            val currentTables = current.tables().associateBy { it.name }
            val allNames = (previousTables.keys + currentTables.keys).sortedBy { it.value }
            val added = mutableListOf<ConfigTableChange>()
            val removed = mutableListOf<ConfigTableChange>()
            val changed = mutableListOf<ConfigTableChange>()

            for (name in allNames) {
                val before = previousTables[name]
                val after = currentTables[name]
                when {
                    before == null && after != null -> added += after.toChange(previousSize = null)
                    before != null && after == null -> removed += before.toChange(currentSize = null)
                    before != null && after != null && before.fingerprint() != after.fingerprint() -> {
                        changed += after.toChange(previousSize = before.size)
                    }
                }
            }

            return ConfigSnapshotDiff(
                previousRevision = previous.revision,
                currentRevision = current.revision,
                addedTables = added,
                removedTables = removed,
                changedTables = changed,
            )
        }
    }
}

/**
 * Summary of one changed table.
 */
data class ConfigTableChange(
    val name: ConfigTableName,
    val keyType: String?,
    val rowType: String,
    val previousSize: Int?,
    val currentSize: Int?,
)

private fun ConfigTable<*>.toChange(
    previousSize: Int? = size,
    currentSize: Int? = size,
): ConfigTableChange {
    return ConfigTableChange(
        name = name,
        keyType = (this as? KeyedConfigTable<*, *>)?.keyType?.let {
            it.qualifiedName ?: it.simpleName ?: "unknown"
        },
        rowType = rowType.qualifiedName ?: rowType.simpleName ?: "unknown",
        previousSize = previousSize,
        currentSize = currentSize,
    )
}

private fun ConfigTable<*>.fingerprint(): ConfigTableFingerprint {
    val keyedRows = when (this) {
        is KeyedConfigTable<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            val keyed = this as KeyedConfigTable<Any, Any>
            keyed.keys.map { key -> key to keyed[key] }
        }
        else -> null
    }
    return ConfigTableFingerprint(
        keyType = (this as? KeyedConfigTable<*, *>)?.keyType,
        rowType = rowType,
        size = size,
        rows = all().toList(),
        keyedRows = keyedRows,
    )
}

private data class ConfigTableFingerprint(
    val keyType: Any?,
    val rowType: Any,
    val size: Int,
    val rows: List<Any>,
    val keyedRows: List<Pair<Any?, Any?>>?,
)
