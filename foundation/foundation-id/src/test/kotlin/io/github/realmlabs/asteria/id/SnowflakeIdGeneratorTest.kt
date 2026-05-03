package io.github.realmlabs.asteria.id

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SnowflakeIdGeneratorTest {
    @Test
    fun nextIdReturnsIncreasingIdsForSameWorker() {
        val timestamps = QueueTimestampSource(1_735_660_800_100L, 1_735_660_800_100L, 1_735_660_800_101L)
        val generator = SnowflakeIdGenerator(WorkerId(3), timestampSource = timestamps)

        val first = generator.nextId()
        val second = generator.nextId()
        val third = generator.nextId()

        assertTrue(first < second)
        assertTrue(second < third)
    }

    @Test
    fun sameTimestampAndDifferentWorkersReturnDifferentIds() {
        val first = SnowflakeIdGenerator(WorkerId(1), timestampSource = FixedTimestampSource(1_735_660_800_100L))
        val second = SnowflakeIdGenerator(WorkerId(2), timestampSource = FixedTimestampSource(1_735_660_800_100L))

        assertNotEquals(first.nextId(), second.nextId())
    }

    @Test
    fun sequenceOverflowWaitsForNextMillis() {
        val options = SnowflakeIdGeneratorOptions(workerIdBits = 1, sequenceBits = 1)
        val timestamps = QueueTimestampSource(
            1_735_660_800_100L,
            1_735_660_800_100L,
            1_735_660_800_100L,
            1_735_660_800_101L,
        )
        val generator = SnowflakeIdGenerator(WorkerId(1), options, timestamps)

        val first = generator.nextId()
        val second = generator.nextId()
        val third = generator.nextId()

        assertTrue(first < second)
        assertTrue(second < third)
    }

    @Test
    fun clockMovingBackwardsFailsFast() {
        val generator = SnowflakeIdGenerator(
            WorkerId(1),
            timestampSource = QueueTimestampSource(1_735_660_800_100L, 1_735_660_800_099L),
        )

        generator.nextId()

        assertFailsWith<IllegalStateException> {
            generator.nextId()
        }
    }

    @Test
    fun timestampBeforeEpochFailsFast() {
        val generator = SnowflakeIdGenerator(WorkerId(1), timestampSource = FixedTimestampSource(1_735_660_799_999L))

        assertFailsWith<IllegalStateException> {
            generator.nextId()
        }
    }

    @Test
    fun workerIdMustFitConfiguredBits() {
        assertFailsWith<IllegalArgumentException> {
            SnowflakeIdGenerator(WorkerId(2), SnowflakeIdGeneratorOptions(workerIdBits = 1))
        }
    }
}

private class FixedTimestampSource(private val timestamp: Long) : TimestampSource {
    override fun currentTimeMillis(): Long = timestamp
}

private class QueueTimestampSource(vararg timestamps: Long) : TimestampSource {
    private val queue = ArrayDeque(timestamps.toList())
    private var last = timestamps.last()

    override fun currentTimeMillis(): Long {
        val next = queue.removeFirstOrNull() ?: last
        last = next
        return next
    }
}
