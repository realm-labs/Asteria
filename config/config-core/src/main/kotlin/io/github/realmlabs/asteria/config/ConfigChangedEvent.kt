package io.github.realmlabs.asteria.config

/**
 * Business-facing event for a published config change.
 *
 * The config framework reports that a new revision has replaced a previous revision, which tables changed, and keyed
 * table key summaries when available. It does not expose row-level payload patches, choose actor targets, or broadcast
 * to actors. Business code should forward this event through its own event bus or actor routing model, then let each
 * actor handle the current revision idempotently.
 *
 * This shape is intentionally the same for online and offline data. Online actors can react when the application
 * forwards the event. Offline actors should compare their last handled config revision with [currentRevision] when they
 * are loaded again and run the same business migration logic against [current].
 */
data class ConfigChangedEvent(
    val previousRevision: ConfigRevision,
    val currentRevision: ConfigRevision,
    val current: ConfigSnapshot,
    val changedTables: Set<ConfigTableName>,
    val changedKeys: Map<ConfigTableName, ConfigTableKeyChange> = emptyMap(),
)
