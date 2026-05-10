package io.github.realmlabs.asteria.id.mongodb

import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters.*
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Updates.set
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.realmlabs.asteria.id.*
import io.github.realmlabs.asteria.observability.MetricTags
import io.github.realmlabs.asteria.observability.Metrics
import io.github.realmlabs.asteria.observability.NoopMetrics
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import org.bson.Document
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.time.Duration
import kotlin.time.Instant

class MongoWorkerIdRepository(
    database: MongoDatabase,
    collectionName: String = "worker_id_leases",
    private val metrics: Metrics = NoopMetrics,
) : WorkerIdRepository {
    private val logger = LoggerFactory.getLogger(MongoWorkerIdRepository::class.java)
    private val leases: MongoCollection<Document> = database.getCollection(collectionName)

    suspend fun ensureIndexes() {
        measured("ensure_indexes") {
            leases.createIndex(Indexes.ascending("owner"))
            leases.createIndex(Indexes.ascending("expiresAtMillis"))
        }
    }

    override suspend fun acquire(
        owner: WorkerIdOwner,
        range: WorkerIdRange,
        ttl: Duration,
        now: Instant,
    ): WorkerIdLease {
        return measured("acquire") {
            require(ttl > Duration.ZERO) { "worker id lease ttl must be positive" }
            expire(now)
            renewCurrentOwnerLease(owner, range, ttl, now)?.let { return@measured it }
            for (id in range.ids()) {
                insertLease(WorkerId(id), owner, ttl, now)?.let { return@measured it }
            }
            throw WorkerIdUnavailableException(owner, range)
        }
    }

    override suspend fun renew(
        lease: WorkerIdLease,
        ttl: Duration,
        now: Instant,
    ): WorkerIdLease? {
        return measured("renew") {
            require(ttl > Duration.ZERO) { "worker id lease ttl must be positive" }
            leases.findOneAndUpdate(
                and(
                    eq("_id", lease.id.value),
                    eq("owner", lease.owner.value),
                    eq("token", lease.token),
                    gte("expiresAtMillis", now.toEpochMilliseconds() + 1),
                ),
                set("expiresAtMillis", (now + ttl).toEpochMilliseconds()),
                FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
            )?.toLease()
        }
    }

    override suspend fun release(lease: WorkerIdLease): Boolean {
        return measured("release") {
            val result = leases.deleteOne(
                and(
                    eq("_id", lease.id.value),
                    eq("owner", lease.owner.value),
                    eq("token", lease.token),
                ),
            )
            result.deletedCount == 1L
        }
    }

    override suspend fun leases(now: Instant): List<WorkerIdLease> {
        return measured("leases") {
            expire(now)
            leases.find()
                .sort(Sorts.ascending("_id"))
                .toList()
                .map { it.toLease() }
        }
    }

    private suspend fun <T> measured(operation: String, block: suspend () -> T): T {
        val tags = MetricTags.of("backend" to "mongodb", "operation" to operation)
        val startedAt = System.nanoTime()
        metrics.counter("asteria.worker_id.repository.operation.total", tags).increment()
        try {
            return block()
        } catch (error: Throwable) {
            metrics.counter("asteria.worker_id.repository.operation.failed.total", tags).increment()
            logger.error("Mongo worker id repository operation failed operation={}", operation, error)
            throw error
        } finally {
            metrics.timer("asteria.worker_id.repository.operation.duration", tags)
                .record((System.nanoTime() - startedAt) / 1_000_000)
        }
    }

    private suspend fun renewCurrentOwnerLease(
        owner: WorkerIdOwner,
        range: WorkerIdRange,
        ttl: Duration,
        now: Instant,
    ): WorkerIdLease? {
        val ids = range.ids().toList()
        val current = leases.find(
            and(
                `in`("_id", ids),
                eq("owner", owner.value),
                gte("expiresAtMillis", now.toEpochMilliseconds() + 1),
            ),
        )
            .sort(Sorts.ascending("_id"))
            .firstOrNull()
            ?.toLease()
            ?: return null
        return renew(current, ttl, now)
    }

    private suspend fun insertLease(
        id: WorkerId,
        owner: WorkerIdOwner,
        ttl: Duration,
        now: Instant,
    ): WorkerIdLease? {
        val lease = WorkerIdLease(
            id = id,
            owner = owner,
            token = UUID.randomUUID().toString(),
            acquiredAt = now,
            expiresAt = now + ttl,
        )
        return try {
            leases.insertOne(lease.toDocument())
            lease
        } catch (error: MongoWriteException) {
            if (error.error.code != DUPLICATE_KEY_ERROR_CODE) {
                throw error
            }
            null
        }
    }

    private suspend fun expire(now: Instant) {
        leases.deleteMany(lte("expiresAtMillis", now.toEpochMilliseconds()))
    }

    private fun WorkerIdLease.toDocument(): Document {
        return Document("_id", id.value)
            .append("owner", owner.value)
            .append("token", token)
            .append("acquiredAtMillis", acquiredAt.toEpochMilliseconds())
            .append("expiresAtMillis", expiresAt.toEpochMilliseconds())
    }

    private fun Document.toLease(): WorkerIdLease {
        return WorkerIdLease(
            id = WorkerId(requiredNumber("_id").toInt()),
            owner = WorkerIdOwner(requiredString("owner")),
            token = requiredString("token"),
            acquiredAt = Instant.fromEpochMilliseconds(requiredNumber("acquiredAtMillis").toLong()),
            expiresAt = Instant.fromEpochMilliseconds(requiredNumber("expiresAtMillis").toLong()),
        )
    }

    private fun Document.requiredString(key: String): String {
        return requireNotNull(getString(key)) { "missing document string field $key" }
    }

    private fun Document.requiredNumber(key: String): Number {
        return requireNotNull(get(key) as? Number) { "missing document number field $key" }
    }

    private companion object {
        const val DUPLICATE_KEY_ERROR_CODE: Int = 11000
    }
}
