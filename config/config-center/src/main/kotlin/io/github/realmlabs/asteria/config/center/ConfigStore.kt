package io.github.realmlabs.asteria.config.center

import kotlinx.coroutines.flow.Flow

/**
 * Opaque revision token returned by a [ConfigStore].
 *
 * The version string is backend-defined. Some stores expose a monotonically increasing revision, while others expose a
 * content hash. Callers should treat it as a compare-and-set token only and avoid inferring global ordering unless the
 * selected backend explicitly guarantees that property.
 */
data class ConfigRevision(
    val version: String,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(version.isNotBlank()) { "config revision version must not be blank" }
    }
}

/**
 * Raw config payload stored at a concrete [path].
 *
 * [bytes] are the exact payload written to the config center. Typed decoding is intentionally handled by
 * [RuntimeConfigRepository] so the storage contract stays backend-neutral.
 */
data class ConfigEntry(
    val path: ConfigPath,
    val bytes: ByteArray,
    val revision: ConfigRevision,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is ConfigEntry) {
            return false
        }
        return path == other.path &&
                bytes.contentEquals(other.bytes) &&
                revision == other.revision
    }

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + revision.hashCode()
        return result
    }
}

/**
 * Watch scope used by [ConfigStore.watch].
 *
 * [Value] watches the exact path only.
 * [Children] watches direct children of the path and does not include deeper descendants.
 * [Tree] watches the path plus any descendant path.
 *
 * Backends may implement these scopes with different native primitives, but callers should code to the semantic
 * contract above rather than assuming a specific backend event model.
 */
enum class ConfigWatchMode {
    Value,
    Children,
    Tree,
}

/**
 * Low-level change event emitted by [ConfigWatch].
 *
 * Watch streams are edge-triggered and represent change notifications, not complete snapshots. Consumers that need a
 * consistent current view should resync through [ConfigStore.get] or [ConfigStore.children] when needed.
 */
sealed interface ConfigEvent {
    val path: ConfigPath

    /**
     * Synthetic event emitted by higher-level adapters after a watch was rebuilt.
     *
     * The underlying store does not have to emit this event directly. It means callers should treat the watch as
     * re-established and refresh any cached snapshot because changes may have been missed while the watch was down.
     */
    data class Resynced(
        override val path: ConfigPath,
        val mode: ConfigWatchMode,
    ) : ConfigEvent

    /**
     * The path now exists with the supplied [entry].
     *
     * For [ConfigWatchMode.Children] and [ConfigWatchMode.Tree], the event path is the changed child path rather than
     * the watched root.
     */
    data class Upserted(
        override val path: ConfigPath,
        val entry: ConfigEntry,
    ) : ConfigEvent

    /**
     * The path was removed.
     *
     * [previous] is best-effort. Some backends can provide the last known value, while others can only report that the
     * path disappeared.
     */
    data class Deleted(
        override val path: ConfigPath,
        val previous: ConfigEntry?,
    ) : ConfigEvent
}

/**
 * Handle for an active watch stream.
 *
 * The [events] flow only reports future changes after the watch is created; it does not include an initial snapshot.
 * Call [close] when the consumer no longer needs updates so backend listeners can be released promptly.
 */
interface ConfigWatch : AutoCloseable {
    val events: Flow<ConfigEvent>
}

/**
 * Runtime-neutral config center contract.
 *
 * Reads are point-in-time operations. [watch] provides best-effort change notifications, but implementations are not
 * required to buffer every missed event across disconnects; callers that need stronger guarantees should re-read state
 * after watch recreation or consume through [RuntimeConfigRepository], which already does that reconciliation.
 */
interface ConfigStore {
    /**
     * Returns the current value at [path], or `null` when the path does not exist.
     */
    suspend fun get(path: ConfigPath): ConfigEntry?

    /**
     * Returns the current direct children under [path].
     *
     * Descendants deeper than one level are excluded.
     */
    suspend fun children(path: ConfigPath): List<ConfigEntry>

    /**
     * Starts watching [path] with the requested [mode].
     *
     * The returned watch begins from future changes only. If the underlying watch flow completes or fails, the watch is
     * considered dead; callers must rebuild it themselves unless they are using a higher-level helper that already
     * implements retry.
     */
    fun watch(
        path: ConfigPath,
        mode: ConfigWatchMode = ConfigWatchMode.Value,
    ): ConfigWatch

    /**
     * Writes [bytes] to [path].
     *
     * When [expectedRevision] is provided, the write succeeds only if the backend still sees the same revision. A
     * failed compare-and-set throws [ConfigRevisionMismatchException].
     */
    suspend fun put(
        path: ConfigPath,
        bytes: ByteArray,
        expectedRevision: ConfigRevision? = null,
    ): ConfigRevision

    /**
     * Writes [bytes] to [path] without requiring a previous revision.
     *
     * This operation means "the final value should be these bytes" whether the entry already exists or not. Backends
     * that need separate create/update calls should hide those details here and retry backend races internally.
     */
    suspend fun upsert(
        path: ConfigPath,
        bytes: ByteArray,
    ): ConfigRevision {
        return put(path, bytes)
    }

    /**
     * Reads the current entry, computes a replacement, and writes it with compare-and-set retry semantics.
     *
     * Returning `null` from [transform] skips the write and returns `null`. The default implementation retries when a
     * backend reports [ConfigRevisionMismatchException], so [transform] may be invoked more than once. Backends with
     * native create-if-absent CAS should override this method so updates that start from a missing value are protected
     * from concurrent creates too.
     */
    suspend fun update(
        path: ConfigPath,
        transform: suspend (current: ConfigEntry?) -> ByteArray?,
    ): ConfigEntry? {
        while (true) {
            val current = get(path)
            val bytes = transform(current?.copy(bytes = current.bytes.copyOf())) ?: return null
            try {
                val revision = if (current == null) {
                    upsert(path, bytes)
                } else {
                    put(path, bytes, current.revision)
                }
                return ConfigEntry(path, bytes.copyOf(), revision)
            } catch (_: ConfigRevisionMismatchException) {
                continue
            }
        }
    }

    /**
     * Deletes [path].
     *
     * When [expectedRevision] is provided, the delete succeeds only if the backend still sees the same revision. A
     * failed compare-and-set throws [ConfigRevisionMismatchException].
     */
    suspend fun delete(
        path: ConfigPath,
        expectedRevision: ConfigRevision? = null,
    )
}

/**
 * Compare-and-set failure raised by [ConfigStore.put] and [ConfigStore.delete].
 */
class ConfigRevisionMismatchException(
    val path: ConfigPath,
    val expected: ConfigRevision?,
    val actual: ConfigRevision?,
) : IllegalStateException("config revision mismatch at $path, expected=$expected, actual=$actual")
