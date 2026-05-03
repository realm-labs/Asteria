package io.github.realmlabs.asteria.id

import java.io.Serializable
import java.time.Instant

@JvmInline
value class WorkerId(val value: Int) : Serializable {
    init {
        require(value >= 0) { "worker id must not be negative" }
    }

    override fun toString(): String = value.toString()
}

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

@JvmInline
value class WorkerIdOwner(val value: String) : Serializable {
    init {
        require(value.isNotBlank()) { "worker id owner must not be blank" }
    }

    override fun toString(): String = value
}

data class WorkerIdLease(
    val id: WorkerId,
    val owner: WorkerIdOwner,
    val token: String,
    val acquiredAt: Instant,
    val expiresAt: Instant,
) : Serializable {
    init {
        require(expiresAt.isAfter(acquiredAt)) { "worker id lease expiresAt must be after acquiredAt" }
    }

    fun isExpired(now: Instant): Boolean {
        return !expiresAt.isAfter(now)
    }
}
