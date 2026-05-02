package io.github.mikai233.asteria.game.random

import kotlin.random.Random

/**
 * Convenience random helpers for common gameplay choices.
 */
object GameRandom {
    /**
     * Rolls [rate] once.
     */
    fun roll(
        rate: Rate,
        random: Random = Random.Default,
    ): Boolean {
        return rate.roll(random)
    }

    /**
     * Builds a temporary [WeightedTable] from [values] and picks one value.
     *
     * Prefer constructing a [WeightedTable] once when the same pool is used repeatedly.
     */
    fun <T> weighted(
        values: Iterable<T>,
        random: Random = Random.Default,
        weight: (T) -> Long,
    ): T {
        return WeightedTable.from(values, weight).pick(random)
    }

    /**
     * Shuffles [values] and returns at most [count] values.
     *
     * This is an unweighted choice without replacement.
     */
    fun <T> shuffleTake(
        values: Iterable<T>,
        count: Int,
        random: Random = Random.Default,
    ): List<T> {
        require(count >= 0) { "shuffle take count must not be negative" }
        val shuffled = values.toMutableList()
        shuffled.shuffle(random)
        return shuffled.take(count)
    }
}
