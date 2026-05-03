package io.github.realmlabs.asteria.game.numeric

import kotlin.random.Random
import kotlin.random.nextInt
import kotlin.random.nextLong

/**
 * Inclusive integer range value commonly used by gameplay configuration.
 */
data class IntRangeValue(
    /**
     * Inclusive lower bound.
     */
    val min: Int,
    /**
     * Inclusive upper bound.
     */
    val max: Int,
) {
    init {
        require(min <= max) { "int range min must not exceed max" }
    }

    /**
     * Returns true when [value] is inside this inclusive range.
     */
    fun contains(value: Int): Boolean = value in min..max

    /**
     * Clamps [value] to this inclusive range.
     */
    fun clamp(value: Int): Int = GameNumbers.clamp(value, min, max)

    /**
     * Picks one integer from this inclusive range.
     */
    fun random(random: Random = Random.Default): Int {
        return random.nextInt(min..max)
    }
}

/**
 * Inclusive long range value commonly used by gameplay configuration.
 */
data class LongRangeValue(
    /**
     * Inclusive lower bound.
     */
    val min: Long,
    /**
     * Inclusive upper bound.
     */
    val max: Long,
) {
    init {
        require(min <= max) { "long range min must not exceed max" }
    }

    /**
     * Returns true when [value] is inside this inclusive range.
     */
    fun contains(value: Long): Boolean = value in min..max

    /**
     * Clamps [value] to this inclusive range.
     */
    fun clamp(value: Long): Long = GameNumbers.clamp(value, min, max)

    /**
     * Picks one long from this inclusive range.
     */
    fun random(random: Random = Random.Default): Long {
        return random.nextLong(min..max)
    }
}
