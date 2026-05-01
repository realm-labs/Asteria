package io.github.mikai233.asteria.game.random

import kotlin.random.Random

/**
 * One weighted value in a [WeightedTable].
 */
data class WeightedEntry<T>(
    /**
     * Value returned when this entry is selected.
     */
    val value: T,
    /**
     * Positive relative weight. The absolute unit is caller-defined.
     */
    val weight: Long,
) {
    init {
        require(weight > 0) { "weighted entry weight must be positive" }
    }
}

/**
 * Immutable weighted random table.
 *
 * The table uses `Long` weights so gameplay configs can safely use large values without overflowing `Int`.
 * The sum of all weights must still fit in `Long`; overflowing totals fail fast during construction.
 */
class WeightedTable<T>(
    entries: List<WeightedEntry<T>>,
) {
    private val entries: List<WeightedEntry<T>> = entries.toList()
    private val totalWeight: Long = this.entries.fold(0L) { total, entry ->
        Math.addExact(total, entry.weight)
    }

    init {
        require(this.entries.isNotEmpty()) { "weighted table must not be empty" }
        require(totalWeight > 0) { "weighted table total weight must be positive" }
    }

    /**
     * Returns the entries in the same order used by the picker.
     */
    fun entries(): List<WeightedEntry<T>> = entries

    /**
     * Returns the sum of all entry weights.
     */
    fun totalWeight(): Long = totalWeight

    /**
     * Picks one value by weight.
     */
    fun pick(random: Random = Random.Default): T {
        return pickEntry(random).value
    }

    /**
     * Picks one weighted entry by weight.
     */
    fun pickEntry(random: Random = Random.Default): WeightedEntry<T> {
        var cursor = random.nextLong(totalWeight)
        entries.forEach { entry ->
            if (cursor < entry.weight) {
                return entry
            }
            cursor -= entry.weight
        }
        return entries.last()
    }

    /**
     * Picks [count] values by weight. Values can appear more than once.
     */
    fun pickManyWithReplacement(
        count: Int,
        random: Random = Random.Default,
    ): List<T> {
        require(count >= 0) { "weighted pick count must not be negative" }
        return List(count) { pick(random) }
    }

    /**
     * Picks [count] values by weight and removes each selected entry before the next pick.
     *
     * This is useful for reward pools where the same configured entry cannot be selected twice in one draw.
     */
    fun pickManyWithoutReplacement(
        count: Int,
        random: Random = Random.Default,
    ): List<T> {
        require(count >= 0) { "weighted pick count must not be negative" }
        require(count <= entries.size) { "weighted pick count must not exceed table size" }
        val remaining = entries.toMutableList()
        val result = mutableListOf<T>()
        repeat(count) {
            val table = WeightedTable(remaining)
            val picked = table.pickEntry(random)
            result += picked.value
            remaining.remove(picked)
        }
        return result
    }

    companion object {
        /**
         * Creates a table from `(value, weight)` pairs.
         */
        fun <T> of(entries: Iterable<Pair<T, Long>>): WeightedTable<T> {
            return WeightedTable(entries.map { (value, weight) -> WeightedEntry(value, weight) })
        }

        /**
         * Creates a table from values and a weight selector.
         */
        fun <T> from(
            values: Iterable<T>,
            weight: (T) -> Long,
        ): WeightedTable<T> {
            return WeightedTable(values.map { WeightedEntry(it, weight(it)) })
        }
    }
}
