package io.github.mikai233.asteria.config

import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory diagnostics for config reloads.
 *
 * The monitor is process-local and intentionally small: it keeps the latest status and a bounded recent history for GM
 * pages or operational diagnostics.
 */
class ConfigReloadMonitor(
    private val maxHistory: Int = 50,
) : ConfigReloadListener, ConfigReloadFailureListener {
    private val nextId = AtomicLong()
    private val records: ArrayDeque<ConfigReloadRecord> = ArrayDeque()

    @Volatile
    private var lastSuccess: ConfigReloadRecord.Success? = null

    @Volatile
    private var lastFailure: ConfigReloadRecord.Failure? = null

    init {
        require(maxHistory > 0) { "config reload monitor max history must be positive" }
    }

    override suspend fun reloaded(result: ConfigReloadResult) {
        append(
            ConfigReloadRecord.Success(
                id = nextId.incrementAndGet(),
                occurredAt = Instant.now(),
                previousRevision = result.previous?.revision,
                currentRevision = result.current.revision,
                diff = ConfigSnapshotDiff.between(result.previous, result.current),
            ),
        )
    }

    override suspend fun failed(event: ConfigReloadFailed) {
        append(
            ConfigReloadRecord.Failure(
                id = nextId.incrementAndGet(),
                occurredAt = event.occurredAt,
                signal = event.signal,
                message = event.error.message ?: event.error::class.qualifiedName ?: "unknown",
            ),
        )
    }

    @Synchronized
    fun status(current: ConfigSnapshot?): ConfigReloadStatus {
        return ConfigReloadStatus(
            currentRevision = current?.revision,
            lastSuccess = lastSuccess,
            lastFailure = lastFailure,
            recent = records.toList().asReversed(),
        )
    }

    @Synchronized
    fun history(limit: Int = maxHistory): List<ConfigReloadRecord> {
        require(limit > 0) { "config reload history limit must be positive" }
        return records.toList().asReversed().take(limit)
    }

    @Synchronized
    private fun append(record: ConfigReloadRecord) {
        records += record
        while (records.size > maxHistory) {
            records.removeFirst()
        }
        when (record) {
            is ConfigReloadRecord.Success -> lastSuccess = record
            is ConfigReloadRecord.Failure -> lastFailure = record
        }
    }
}

/**
 * Current reload diagnostic state.
 */
data class ConfigReloadStatus(
    val currentRevision: ConfigRevision?,
    val lastSuccess: ConfigReloadRecord.Success?,
    val lastFailure: ConfigReloadRecord.Failure?,
    val recent: List<ConfigReloadRecord>,
)

/**
 * One reload history record.
 */
sealed interface ConfigReloadRecord {
    val id: Long
    val occurredAt: Instant

    data class Success(
        override val id: Long,
        override val occurredAt: Instant,
        val previousRevision: ConfigRevision?,
        val currentRevision: ConfigRevision,
        val diff: ConfigSnapshotDiff,
    ) : ConfigReloadRecord

    data class Failure(
        override val id: Long,
        override val occurredAt: Instant,
        val signal: ConfigReloadSignal?,
        val message: String,
    ) : ConfigReloadRecord
}
