package io.github.realmlabs.asteria.game.random

import io.github.realmlabs.asteria.game.numeric.GameNumbers
import java.math.BigInteger
import kotlin.random.Random

/**
 * Probability represented in parts per million.
 *
 * This avoids scattering conventions like "10000 means 100%" across gameplay code.
 *
 * `Rate` is also useful for integer percentage-like calculations. Those calculations intentionally use integer
 * division, so `Rate.percent(33).applyTo(10)` returns `3`. Use `Fraction` when exact rational formulas must be carried
 * through multiple operations before rounding.
 */
@JvmInline
value class Rate private constructor(val perMillion: Int) {
    init {
        require(perMillion in 0..SCALE) { "rate per million must be in 0..$SCALE" }
    }

    /**
     * Rolls this probability once.
     */
    fun roll(random: Random = Random.Default): Boolean {
        return perMillion > 0 && random.nextInt(SCALE) < perMillion
    }

    /**
     * Applies this rate to [value] using integer division.
     *
     * The result is truncated toward zero. For example, 25% of 99 is 24.
     */
    fun applyTo(value: Long): Long {
        return ratio(value, perMillion)
    }

    /**
     * Applies this rate to [value] using integer division.
     *
     * The result is truncated toward zero. For example, 25% of 99 is 24.
     */
    fun applyTo(value: Int): Int {
        return GameNumbers.toIntExact(applyTo(value.toLong()))
    }

    /**
     * Increases [value] by this rate, capped by [max].
     *
     * For example, increasing 1000 by 25% returns 1250.
     */
    fun increase(
        value: Long,
        max: Long = Long.MAX_VALUE,
    ): Long {
        return GameNumbers.safeAdd(value, applyTo(value), max)
    }

    /**
     * Increases [value] by this rate, capped by [max].
     *
     * For example, increasing 1000 by 25% returns 1250.
     */
    fun increase(
        value: Int,
        max: Int = Int.MAX_VALUE,
    ): Int {
        return GameNumbers.safeAdd(value, applyTo(value), max)
    }

    /**
     * Decreases [value] by this rate, floored by [min].
     *
     * For example, decreasing 1000 by 25% returns 750.
     */
    fun decrease(
        value: Long,
        min: Long = 0,
    ): Long {
        return GameNumbers.safeSubtract(value, applyTo(value), min)
    }

    /**
     * Decreases [value] by this rate, floored by [min].
     *
     * For example, decreasing 1000 by 25% returns 750.
     */
    fun decrease(
        value: Int,
        min: Int = 0,
    ): Int {
        return GameNumbers.safeSubtract(value, applyTo(value), min)
    }

    companion object {
        /**
         * Internal probability scale. `1_000_000` means 100%.
         */
        const val SCALE: Int = 1_000_000

        /**
         * A probability that never succeeds.
         */
        val Never: Rate = Rate(0)

        /**
         * A probability that always succeeds.
         */
        val Always: Rate = Rate(SCALE)

        /**
         * Creates a rate from parts per million.
         */
        fun perMillion(value: Int): Rate = Rate(value)

        /**
         * Creates a rate from basis points where `10_000` means 100%.
         */
        fun basisPoints(value: Int): Rate {
            require(value in 0..10_000) { "basis point rate must be in 0..10000" }
            return Rate(value * 100)
        }

        /**
         * Creates a rate from whole percent where `100` means 100%.
         */
        fun percent(value: Int): Rate {
            require(value in 0..100) { "percent rate must be in 0..100" }
            return Rate(value * 10_000)
        }

        private val BIG_SCALE: BigInteger = BigInteger.valueOf(SCALE.toLong())

        private fun ratio(value: Long, perMillion: Int): Long {
            return BigInteger.valueOf(value)
                .multiply(BigInteger.valueOf(perMillion.toLong()))
                .divide(BIG_SCALE)
                .longValueExact()
        }
    }
}
