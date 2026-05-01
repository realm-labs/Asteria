package io.github.mikai233.asteria.script.job.mongodb

import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.lte
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Updates.set
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.mikai233.asteria.script.job.ScriptJobPermitLease
import io.github.mikai233.asteria.script.job.ScriptJobPermitLeaseId
import io.github.mikai233.asteria.script.job.ScriptJobPermitRepository
import org.bson.Document
import java.util.UUID

/**
 * MongoDB-backed distributed permit repository.
 *
 * The repository stores one document per permit slot. Acquiring a permit is an insert into a bounded slot range, so
 * concurrent GM workers cannot exceed the configured limit even when they start large script batches at the same time.
 */
class MongoScriptJobPermitRepository(
    database: MongoDatabase,
    collectionName: String = "script_job_permits",
) : ScriptJobPermitRepository {
    private val permits: MongoCollection<Document> = database.getCollection(collectionName)

    suspend fun ensureIndexes() {
        permits.createIndex(Indexes.compoundIndex(Indexes.ascending("pool"), Indexes.ascending("leaseUntilMillis")))
        permits.createIndex(Indexes.ascending("leaseId"))
    }

    override suspend fun acquire(
        pool: String,
        owner: String,
        permits: Int,
        limit: Int,
        leaseUntilMillis: Long,
        nowMillis: Long,
    ): ScriptJobPermitLease? {
        require(pool.isNotBlank()) { "script job permit pool must not be blank" }
        require(owner.isNotBlank()) { "script job permit owner must not be blank" }
        require(permits > 0) { "script job permit count must be positive" }
        require(limit > 0) { "script job permit limit must be positive" }
        require(permits <= limit) { "script job permit count must not exceed limit" }
        require(leaseUntilMillis > nowMillis) { "script job permit lease must be in the future" }
        expire(pool, nowMillis)
        val leaseId = ScriptJobPermitLeaseId(UUID.randomUUID().toString())
        val acquired = mutableListOf<String>()
        for (slot in 0 until limit) {
            if (acquired.size == permits) {
                break
            }
            val slotId = slotId(pool, slot)
            val inserted = try {
                this.permits.insertOne(
                    Document("_id", slotId)
                        .append("pool", pool)
                        .append("slot", slot)
                        .append("leaseId", leaseId.value)
                        .append("owner", owner)
                        .append("leaseUntilMillis", leaseUntilMillis)
                        .append("acquiredAtMillis", nowMillis),
                )
                true
            } catch (error: MongoWriteException) {
                if (error.error.code != DUPLICATE_KEY_ERROR_CODE) {
                    throw error
                }
                false
            }
            if (inserted) {
                acquired += slotId
            }
        }
        if (acquired.size != permits) {
            releaseSlots(acquired)
            return null
        }
        return ScriptJobPermitLease(
            id = leaseId,
            pool = pool,
            owner = owner,
            permits = permits,
            leaseUntilMillis = leaseUntilMillis,
            acquiredAtMillis = nowMillis,
        )
    }

    override suspend fun renew(
        lease: ScriptJobPermitLease,
        leaseUntilMillis: Long,
        nowMillis: Long,
    ): Boolean {
        require(leaseUntilMillis > nowMillis) { "script job permit lease must be in the future" }
        expire(lease.pool, nowMillis)
        val result = permits.updateMany(
            and(
                eq("pool", lease.pool),
                eq("leaseId", lease.id.value),
                eq("owner", lease.owner),
            ),
            set("leaseUntilMillis", leaseUntilMillis),
        )
        return result.modifiedCount == lease.permits.toLong()
    }

    override suspend fun release(lease: ScriptJobPermitLease): Boolean {
        val result = permits.deleteMany(
            and(
                eq("pool", lease.pool),
                eq("leaseId", lease.id.value),
                eq("owner", lease.owner),
            ),
        )
        return result.deletedCount > 0
    }

    private suspend fun expire(pool: String, nowMillis: Long) {
        permits.deleteMany(and(eq("pool", pool), lte("leaseUntilMillis", nowMillis)))
    }

    private suspend fun releaseSlots(slotIds: List<String>) {
        if (slotIds.isEmpty()) {
            return
        }
        slotIds.forEach { permits.deleteOne(eq("_id", it)) }
    }

    private fun slotId(pool: String, slot: Int): String = "$pool:$slot"

    private companion object {
        const val DUPLICATE_KEY_ERROR_CODE: Int = 11000
    }
}
