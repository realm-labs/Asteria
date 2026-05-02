package io.github.mikai233.asteria.id.mongodb

import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.gte
import com.mongodb.client.model.Filters.`in`
import com.mongodb.client.model.Filters.lte
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Updates.set
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.mikai233.asteria.id.WorkerId
import io.github.mikai233.asteria.id.WorkerIdLease
import io.github.mikai233.asteria.id.WorkerIdOwner
import io.github.mikai233.asteria.id.WorkerIdRange
import io.github.mikai233.asteria.id.WorkerIdRepository
import io.github.mikai233.asteria.id.WorkerIdUnavailableException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import org.bson.Document

class MongoWorkerIdRepository(
    database: MongoDatabase,
    collectionName: String = "worker_id_leases",
) : WorkerIdRepository {
    private val leases: MongoCollection<Document> = database.getCollection(collectionName)

    suspend fun ensureIndexes() {
        leases.createIndex(Indexes.ascending("owner"))
        leases.createIndex(Indexes.ascending("expiresAtMillis"))
    }

    override suspend fun acquire(
        owner: WorkerIdOwner,
        range: WorkerIdRange,
        ttl: Duration,
        now: Instant,
    ): WorkerIdLease {
        require(!ttl.isNegative && !ttl.isZero) { "worker id lease ttl must be positive" }
        expire(now)
        renewCurrentOwnerLease(owner, range, ttl, now)?.let { return it }
        for (id in range.ids()) {
            insertLease(WorkerId(id), owner, ttl, now)?.let { return it }
        }
        throw WorkerIdUnavailableException(owner, range)
    }

    override suspend fun renew(
        lease: WorkerIdLease,
        ttl: Duration,
        now: Instant,
    ): WorkerIdLease? {
        require(!ttl.isNegative && !ttl.isZero) { "worker id lease ttl must be positive" }
        return leases.findOneAndUpdate(
            and(
                eq("_id", lease.id.value),
                eq("owner", lease.owner.value),
                eq("token", lease.token),
                gte("expiresAtMillis", now.toEpochMilli() + 1),
            ),
            set("expiresAtMillis", now.plus(ttl).toEpochMilli()),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.toLease()
    }

    override suspend fun release(lease: WorkerIdLease): Boolean {
        val result = leases.deleteOne(
            and(
                eq("_id", lease.id.value),
                eq("owner", lease.owner.value),
                eq("token", lease.token),
            ),
        )
        return result.deletedCount == 1L
    }

    override suspend fun leases(now: Instant): List<WorkerIdLease> {
        expire(now)
        return leases.find()
            .sort(Sorts.ascending("_id"))
            .toList()
            .map { it.toLease() }
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
                gte("expiresAtMillis", now.toEpochMilli() + 1),
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
            expiresAt = now.plus(ttl),
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
        leases.deleteMany(lte("expiresAtMillis", now.toEpochMilli()))
    }

    private fun WorkerIdLease.toDocument(): Document {
        return Document("_id", id.value)
            .append("owner", owner.value)
            .append("token", token)
            .append("acquiredAtMillis", acquiredAt.toEpochMilli())
            .append("expiresAtMillis", expiresAt.toEpochMilli())
    }

    private fun Document.toLease(): WorkerIdLease {
        return WorkerIdLease(
            id = WorkerId(requiredNumber("_id").toInt()),
            owner = WorkerIdOwner(requiredString("owner")),
            token = requiredString("token"),
            acquiredAt = Instant.ofEpochMilli(requiredNumber("acquiredAtMillis").toLong()),
            expiresAt = Instant.ofEpochMilli(requiredNumber("expiresAtMillis").toLong()),
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
