package io.github.realmlabs.asteria.id

interface IdGenerator {
    fun nextId(): Long
}

fun interface TimestampSource {
    fun currentTimeMillis(): Long
}

object SystemTimestampSource : TimestampSource {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}

data class SnowflakeIdGeneratorOptions(
    val epochMillis: Long = 1_735_660_800_000L,
    val workerIdBits: Int = 10,
    val sequenceBits: Int = 12,
) {
    init {
        require(workerIdBits in 1..20) { "workerIdBits must be in 1..20" }
        require(sequenceBits in 1..20) { "sequenceBits must be in 1..20" }
        require(workerIdBits + sequenceBits < Long.SIZE_BITS - 1) {
            "workerIdBits + sequenceBits must leave room for a positive timestamp"
        }
    }
}

class SnowflakeIdGenerator(
    workerId: WorkerId,
    private val options: SnowflakeIdGeneratorOptions = SnowflakeIdGeneratorOptions(),
    private val timestampSource: TimestampSource = SystemTimestampSource,
) : IdGenerator {
    private val maxWorkerId = (1L shl options.workerIdBits) - 1
    private val sequenceMask = (1L shl options.sequenceBits) - 1
    private val workerIdValue = workerId.value.toLong()
    private val workerIdShift = options.sequenceBits
    private val timestampShift = options.sequenceBits + options.workerIdBits

    private var lastTimestamp = -1L
    private var sequence = 0L

    init {
        require(workerIdValue <= maxWorkerId) { "worker id $workerId exceeds max worker id $maxWorkerId" }
    }

    @Synchronized
    override fun nextId(): Long {
        var timestamp = timestampSource.currentTimeMillis()
        check(timestamp >= options.epochMillis) {
            "timestamp $timestamp is before epoch ${options.epochMillis}"
        }
        check(timestamp >= lastTimestamp) {
            "clock moved backwards from $lastTimestamp to $timestamp"
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) and sequenceMask
            if (sequence == 0L) {
                timestamp = waitNextMillis(timestamp)
            }
        } else {
            sequence = 0L
        }
        lastTimestamp = timestamp
        return ((timestamp - options.epochMillis) shl timestampShift) or
                (workerIdValue shl workerIdShift) or
                sequence
    }

    private fun waitNextMillis(currentTimestamp: Long): Long {
        var timestamp = timestampSource.currentTimeMillis()
        while (timestamp <= currentTimestamp) {
            timestamp = timestampSource.currentTimeMillis()
        }
        return timestamp
    }
}
