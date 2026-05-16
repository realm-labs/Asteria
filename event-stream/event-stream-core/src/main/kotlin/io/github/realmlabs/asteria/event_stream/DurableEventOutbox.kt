package io.github.realmlabs.asteria.event_stream

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException

/**
 * Stable id of a durable event outbox record.
 */
@JvmInline
value class DurableEventOutboxRecordId(val value: String) {
    init {
        require(value.isNotBlank()) { "durable event outbox record id must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Publication state of a durable event outbox record.
 */
enum class DurableEventOutboxStatus {
    Pending,
    InFlight,
    Published,
    Failed,
}

/**
 * One durable event waiting for reliable publication.
 */
data class DurableEventOutboxRecord(
    val id: DurableEventOutboxRecordId,
    val event: DurableEventEnvelope,
    val status: DurableEventOutboxStatus = DurableEventOutboxStatus.Pending,
    val attempts: Int = 0,
    val nextAttemptAt: Instant = Instant.EPOCH,
    val lockedUntil: Instant? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = createdAt,
    val publishedAt: Instant? = null,
    val publishResult: DurableEventPublishResult? = null,
    val lastError: String? = null,
) {
    init {
        require(attempts >= 0) { "durable event outbox attempts must not be negative" }
        require(lastError == null || lastError.isNotBlank()) { "durable event outbox lastError must not be blank" }
    }
}

/**
 * Store for durable event outbox records.
 *
 * Implementations usually live in the same transactional datastore as the business state that produced the event.
 */
interface DurableEventOutboxStore {
    suspend fun append(
        event: DurableEventEnvelope,
        id: DurableEventOutboxRecordId = DurableEventOutboxRecordId(UUID.randomUUID().toString()),
        now: Instant = Instant.now(),
    ): DurableEventOutboxRecord

    suspend fun claimDue(
        limit: Int,
        now: Instant = Instant.now(),
        leaseDuration: Duration = Duration.ofSeconds(30),
    ): List<DurableEventOutboxRecord>

    suspend fun markPublished(
        id: DurableEventOutboxRecordId,
        result: DurableEventPublishResult,
        now: Instant = Instant.now(),
    )

    suspend fun markFailed(
        id: DurableEventOutboxRecordId,
        error: Throwable,
        nextAttemptAt: Instant,
        now: Instant = Instant.now(),
    )
}

/**
 * In-memory [DurableEventOutboxStore] for tests and local development.
 */
class InMemoryDurableEventOutboxStore : DurableEventOutboxStore {
    private val records: MutableMap<DurableEventOutboxRecordId, DurableEventOutboxRecord> = linkedMapOf()

    override suspend fun append(
        event: DurableEventEnvelope,
        id: DurableEventOutboxRecordId,
        now: Instant,
    ): DurableEventOutboxRecord {
        check(id !in records) { "duplicate durable event outbox record $id" }
        val record = DurableEventOutboxRecord(
            id = id,
            event = event,
            nextAttemptAt = now,
            createdAt = now,
            updatedAt = now,
        )
        records[id] = record
        return record
    }

    override suspend fun claimDue(
        limit: Int,
        now: Instant,
        leaseDuration: Duration,
    ): List<DurableEventOutboxRecord> {
        require(limit > 0) { "durable event outbox claim limit must be positive" }
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "durable event outbox leaseDuration must be positive" }
        val claimed = records.values
            .asSequence()
            .filter { it.status == DurableEventOutboxStatus.Pending || it.status == DurableEventOutboxStatus.Failed || it.isLeaseExpired(now) }
            .filter { !it.nextAttemptAt.isAfter(now) }
            .take(limit)
            .map { record ->
                record.copy(
                    status = DurableEventOutboxStatus.InFlight,
                    attempts = record.attempts + 1,
                    lockedUntil = now.plus(leaseDuration),
                    updatedAt = now,
                )
            }
            .toList()
        claimed.forEach { records[it.id] = it }
        return claimed
    }

    override suspend fun markPublished(
        id: DurableEventOutboxRecordId,
        result: DurableEventPublishResult,
        now: Instant,
    ) {
        records[id] = record(id).copy(
            status = DurableEventOutboxStatus.Published,
            lockedUntil = null,
            publishedAt = now,
            publishResult = result,
            updatedAt = now,
            lastError = null,
        )
    }

    override suspend fun markFailed(
        id: DurableEventOutboxRecordId,
        error: Throwable,
        nextAttemptAt: Instant,
        now: Instant,
    ) {
        records[id] = record(id).copy(
            status = DurableEventOutboxStatus.Failed,
            lockedUntil = null,
            nextAttemptAt = nextAttemptAt,
            updatedAt = now,
            lastError = error.message ?: error::class.qualifiedName,
        )
    }

    fun records(): List<DurableEventOutboxRecord> {
        return records.values.toList()
    }

    fun record(id: DurableEventOutboxRecordId): DurableEventOutboxRecord {
        return requireNotNull(records[id]) { "durable event outbox record $id not found" }
    }

    private fun DurableEventOutboxRecord.isLeaseExpired(now: Instant): Boolean {
        return status == DurableEventOutboxStatus.InFlight && lockedUntil?.isBefore(now) == true
    }
}

/**
 * Options for [DurableEventOutboxPump].
 */
data class DurableEventOutboxPumpOptions(
    val batchSize: Int = 100,
    val leaseDuration: Duration = Duration.ofSeconds(30),
    val retryDelay: (DurableEventOutboxRecord, Throwable) -> Duration = { _, _ -> Duration.ofSeconds(5) },
) {
    init {
        require(batchSize > 0) { "durable event outbox batchSize must be positive" }
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "durable event outbox leaseDuration must be positive" }
    }
}

/**
 * Summary returned by one outbox drain attempt.
 */
data class DurableEventOutboxDrainResult(
    val claimed: Int,
    val published: Int,
    val failed: Int,
)

/**
 * Publishes due outbox records through a [DurableEventPublisher].
 */
class DurableEventOutboxPump(
    private val store: DurableEventOutboxStore,
    private val publisher: DurableEventPublisher,
    private val options: DurableEventOutboxPumpOptions = DurableEventOutboxPumpOptions(),
) {
    suspend fun drainOnce(now: Instant = Instant.now()): DurableEventOutboxDrainResult {
        val records = store.claimDue(
            limit = options.batchSize,
            now = now,
            leaseDuration = options.leaseDuration,
        )
        var published = 0
        var failed = 0
        for (record in records) {
            try {
                val result = publisher.publish(record.event)
                store.markPublished(record.id, result, Instant.now())
                published += 1
            } catch (error: CancellationException) {
                store.markFailed(record.id, error, nextAttemptAt(record, error), Instant.now())
                throw error
            } catch (error: Throwable) {
                store.markFailed(record.id, error, nextAttemptAt(record, error), Instant.now())
                failed += 1
            }
        }
        return DurableEventOutboxDrainResult(claimed = records.size, published = published, failed = failed)
    }

    private fun nextAttemptAt(
        record: DurableEventOutboxRecord,
        error: Throwable,
    ): Instant {
        val delay = options.retryDelay(record, error)
        require(!delay.isNegative) { "durable event outbox retry delay must not be negative" }
        return Instant.now().plus(delay)
    }
}
