package io.github.realmlabs.asteria.config

/**
 * Table-level difference between two config snapshots.
 *
 * Diff is table-grained for routing, with key-level summaries for keyed tables so callers can cheaply narrow follow-up
 * work while still reading full rows from the current snapshot.
 */
data class ConfigSnapshotDiff(
    val previousRevision: ConfigRevision?,
    val currentRevision: ConfigRevision,
    val addedTables: List<ConfigTableChange> = emptyList(),
    val removedTables: List<ConfigTableChange> = emptyList(),
    val changedTables: List<ConfigTableChange> = emptyList(),
) {
    /**
     * Returns `true` when at least one table was added, removed, or changed.
     */
    val hasChanges: Boolean
        get() = addedTables.isNotEmpty() || removedTables.isNotEmpty() || changedTables.isNotEmpty()

    companion object {
        /**
         * Computes a table-grained diff between [previous] and [current].
         *
         * Equality is based on a structural fingerprint of table metadata and row contents. This is intentionally more
         * expensive than comparing revisions, because a loader may publish a new revision label without changing table
         * payloads or may keep the same label while changing data during tests.
         */
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
                    before == null && after != null -> added += after.toChange(
                        previousSize = null,
                        keyChange = after.addedKeyChange(),
                    )

                    before != null && after == null -> removed += before.toChange(
                        currentSize = null,
                        keyChange = before.removedKeyChange(),
                    )

                    before != null && after != null && before.fingerprint() != after.fingerprint() -> {
                        changed += after.toChange(
                            previousSize = before.size,
                            keyChange = keyChangeBetween(before, after),
                        )
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
 *
 * This type is meant for diagnostics and routing decisions. It is not a row-level patch format.
 */
data class ConfigTableChange(
    val name: ConfigTableName,
    val keyType: String?,
    val rowType: String,
    val previousSize: Int?,
    val currentSize: Int?,
    val keyChange: ConfigTableKeyChange? = null,
)

/**
 * Key-level summary for a changed keyed table.
 *
 * This is diagnostic and routing metadata. Callers should read row payloads from the current snapshot instead of
 * treating this as a row patch format.
 */
data class ConfigTableKeyChange(
    val keyType: String,
    val addedKeys: Set<Any> = emptySet(),
    val removedKeys: Set<Any> = emptySet(),
    val updatedKeys: Set<Any> = emptySet(),
) {
    val changedKeys: Set<Any>
        get() = linkedSetOf<Any>().apply {
            addAll(addedKeys)
            addAll(removedKeys)
            addAll(updatedKeys)
        }
}

private fun ConfigTable<*>.toChange(
    previousSize: Int? = size,
    currentSize: Int? = size,
    keyChange: ConfigTableKeyChange? = null,
): ConfigTableChange {
    return ConfigTableChange(
        name = name,
        keyType = keyTypeName(),
        rowType = rowType.qualifiedName ?: rowType.simpleName ?: "unknown",
        previousSize = previousSize,
        currentSize = currentSize,
        keyChange = keyChange,
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

private fun ConfigTable<*>.addedKeyChange(): ConfigTableKeyChange? {
    val keyed = asKeyedTable() ?: return null
    return ConfigTableKeyChange(
        keyType = keyed.keyTypeName(),
        addedKeys = keyed.keys.mapTo(linkedSetOf()) { it },
    )
}

private fun ConfigTable<*>.removedKeyChange(): ConfigTableKeyChange? {
    val keyed = asKeyedTable() ?: return null
    return ConfigTableKeyChange(
        keyType = keyed.keyTypeName(),
        removedKeys = keyed.keys.mapTo(linkedSetOf()) { it },
    )
}

private fun keyChangeBetween(
    previous: ConfigTable<*>,
    current: ConfigTable<*>,
): ConfigTableKeyChange? {
    val previousKeyed = previous.asKeyedTable()
    val currentKeyed = current.asKeyedTable()
    return when {
        previousKeyed == null && currentKeyed == null -> null
        previousKeyed == null && currentKeyed != null -> current.addedKeyChange()
        previousKeyed != null && currentKeyed == null -> previous.removedKeyChange()
        previousKeyed != null && currentKeyed != null -> keyedChangeBetween(previousKeyed, currentKeyed)
        else -> null
    }
}

private fun keyedChangeBetween(
    previous: KeyedConfigTable<Any, Any>,
    current: KeyedConfigTable<Any, Any>,
): ConfigTableKeyChange {
    if (previous.keyType != current.keyType) {
        return ConfigTableKeyChange(
            keyType = "${previous.keyTypeName()} -> ${current.keyTypeName()}",
            addedKeys = current.keys.mapTo(linkedSetOf()) { it },
            removedKeys = previous.keys.mapTo(linkedSetOf()) { it },
        )
    }

    val previousKeys = previous.keys.toSet()
    return ConfigTableKeyChange(
        keyType = current.keyTypeName(),
        addedKeys = current.keys.filterTo(linkedSetOf()) { it !in previousKeys },
        removedKeys = previous.keys.filterTo(linkedSetOf()) { it !in current.keys },
        updatedKeys = current.keys.filterTo(linkedSetOf()) { key ->
            key in previousKeys && previous[key] != current[key]
        },
    )
}

@Suppress("UNCHECKED_CAST")
private fun ConfigTable<*>.asKeyedTable(): KeyedConfigTable<Any, Any>? {
    return this as? KeyedConfigTable<Any, Any>
}

private fun ConfigTable<*>.keyTypeName(): String? {
    return (this as? KeyedConfigTable<*, *>)?.keyTypeName()
}

private fun KeyedConfigTable<*, *>.keyTypeName(): String {
    return keyType.qualifiedName ?: keyType.simpleName ?: "unknown"
}
