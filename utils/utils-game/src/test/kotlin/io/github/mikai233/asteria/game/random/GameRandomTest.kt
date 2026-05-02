package io.github.mikai233.asteria.game.random

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GameRandomTest {
    @Test
    fun rateSupportsCommonGameScales() {
        assertFalse(Rate.Never.roll(Random(1)))
        assertTrue(Rate.Always.roll(Random(1)))
        assertEquals(250_000, Rate.percent(25).perMillion)
        assertEquals(250_000, Rate.basisPoints(2_500).perMillion)
    }

    @Test
    fun weightedTablePicksOnlyConfiguredValues() {
        val table = WeightedTable.of(
            listOf(
                "sword" to 1L,
                "shield" to 3L,
                "potion" to 6L,
            ),
        )

        val picked = table.pickManyWithReplacement(20, Random(7))

        assertTrue(picked.all { it in setOf("sword", "shield", "potion") })
        assertEquals(10L, table.totalWeight())
    }

    @Test
    fun weightedTableCanPickWithoutReplacement() {
        val table = WeightedTable.of(
            listOf(
                "a" to 1L,
                "b" to 1L,
                "c" to 1L,
            ),
        )

        val picked = table.pickManyWithoutReplacement(3, Random(2))

        assertEquals(3, picked.toSet().size)
        assertFailsWith<IllegalArgumentException> {
            table.pickManyWithoutReplacement(4, Random(2))
        }
    }

    @Test
    fun weightedTableRejectsOverflowingTotalWeight() {
        assertFailsWith<ArithmeticException> {
            WeightedTable.of(
                listOf(
                    "a" to Long.MAX_VALUE,
                    "b" to 1L,
                ),
            )
        }
    }

    @Test
    fun gameRandomShuffleTakeDoesNotExceedSourceSize() {
        assertEquals(
            3,
            GameRandom.shuffleTake(listOf(1, 2, 3), count = 10, random = Random(3)).size,
        )
    }
}
