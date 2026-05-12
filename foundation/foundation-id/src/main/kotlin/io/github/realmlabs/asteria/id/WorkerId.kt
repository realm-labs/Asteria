package io.github.realmlabs.asteria.id

import java.io.Serializable
import kotlin.time.Instant

/**
 * Numeric worker id used by distributed ID generators.
 *
 * The value is deliberately constrained only to non-negative here. Generator implementations, such as
 * [SnowflakeIdGenerator], may impose a smaller upper bound based on their bit layout.
 */
@JvmInline
value class WorkerId(val value: Int) : Serializable {
    init {
        require(value >= 0) { "worker id must not be negative" }
    }

    override fun toString(): String = value.toString()
}

/**
 * Inclusive range from which a [WorkerIdRepository] may lease worker ids.
 */
data class WorkerIdRange(
    val start: WorkerId,
    val endInclusive: WorkerId,
) {
    init {
        require(start.value <= endInclusive.value) {
            "worker id range start ${start.value} must be <= end ${endInclusive.value}"
        }
    }

    operator fun contains(id: WorkerId): Boolean {
        return id.value in start.value..endInclusive.value
    }

    fun ids(): IntRange {
        return start.value..endInclusive.value
    }

    companion object {
        fun of(start: Int, endInclusive: Int): WorkerIdRange {
            return WorkerIdRange(WorkerId(start), WorkerId(endInclusive))
        }
    }
}

/**
 * Stable owner identity used to reacquire or renew a worker-id lease.
 *
 * Use a value that identifies the running process or pod strongly enough for your deployment. Reusing an owner lets a
 * process recover its previous unexpired lease when the repository implementation supports it.
 */
@JvmInline
value class WorkerIdOwner(val value: String) : Serializable {
    init {
        require(value.isNotBlank()) { "worker id owner must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Time-bounded ownership proof for one [WorkerId].
 *
 * The [token] is the fencing value that distinguishes this lease from a later lease for the same numeric id. Code that
 * renews or releases a lease must pass the full object back to the repository so stale owners cannot modify newer
 * ownership.
 */
data class WorkerIdLease(
    val id: WorkerId,
    val owner: WorkerIdOwner,
    val token: String,
    val acquiredAt: Instant,
    val expiresAt: Instant,
) : Serializable {
    init {
        require(expiresAt > acquiredAt) { "worker id lease expiresAt must be after acquiredAt" }
    }

    fun isExpired(now: Instant): Boolean {
        return expiresAt <= now
    }
}
