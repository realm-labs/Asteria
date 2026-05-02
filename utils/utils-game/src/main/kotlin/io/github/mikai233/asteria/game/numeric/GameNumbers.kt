package io.github.mikai233.asteria.game.numeric

import java.lang.Math.addExact
import java.lang.Math.subtractExact

/**
 * Numeric helpers for resource, attribute, and counter calculations.
 *
 * These helpers are intentionally conservative: overflow is converted into caller-provided caps or floors instead of
 * wrapping around.
 */
object GameNumbers {
    /**
     * Clamps [value] to `[min, max]`.
     */
    fun clamp(
        value: Int,
        min: Int,
        max: Int,
    ): Int {
        require(min <= max) { "numeric clamp min must not exceed max" }
        return value.coerceIn(min, max)
    }

    /**
     * Clamps [value] to `[min, max]`.
     */
    fun clamp(
        value: Long,
        min: Long,
        max: Long,
    ): Long {
        require(min <= max) { "numeric clamp min must not exceed max" }
        return value.coerceIn(min, max)
    }

    /**
     * Adds [delta] to [value], capping the result at [max] when it exceeds the allowed range.
     *
     * This prevents resource counters from wrapping when a large reward is added.
     */
    fun safeAdd(
        value: Int,
        delta: Int,
        max: Int = Int.MAX_VALUE,
    ): Int {
        return toIntExact(safeAdd(value.toLong(), delta.toLong(), max.toLong()))
    }

    /**
     * Adds [delta] to [value], capping the result at [max] when it exceeds the allowed range.
     *
     * This prevents resource counters from wrapping when a large reward is added.
     */
    fun safeAdd(
        value: Long,
        delta: Long,
        max: Long = Long.MAX_VALUE,
    ): Long {
        require(max >= value) { "safe add max must not be less than value" }
        val added = runCatching { addExact(value, delta) }
            .getOrElse { if (delta >= 0) Long.MAX_VALUE else Long.MIN_VALUE }
        return added.coerceAtMost(max)
    }

    /**
     * Subtracts [delta] from [value], flooring the result at [min].
     *
     * The default [min] is zero, which matches common non-negative resource counters.
     */
    fun safeSubtract(
        value: Int,
        delta: Int,
        min: Int = 0,
    ): Int {
        return toIntExact(safeSubtract(value.toLong(), delta.toLong(), min.toLong()))
    }

    /**
     * Subtracts [delta] from [value], flooring the result at [min].
     *
     * The default [min] is zero, which matches common non-negative resource counters.
     */
    fun safeSubtract(
        value: Long,
        delta: Long,
        min: Long = 0,
    ): Long {
        require(min <= value) { "safe subtract min must not be greater than value" }
        val subtracted = runCatching { subtractExact(value, delta) }
            .getOrElse { if (delta >= 0) Long.MIN_VALUE else Long.MAX_VALUE }
        return subtracted.coerceAtLeast(min)
    }

    /**
     * Converts [value] to [Int], failing when the value does not fit.
     */
    fun toIntExact(value: Long): Int {
        require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "long value $value cannot fit in int" }
        return value.toInt()
    }
}
