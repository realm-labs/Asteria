package io.github.mikai233.asteria.game.numeric

import io.github.mikai233.asteria.game.random.Rate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GameNumbersTest {
    @Test
    fun rateCanApplyIncreaseAndDecreaseValues() {
        val rate = Rate.basisPoints(2_500)

        assertEquals(250, rate.applyTo(1_000))
        assertEquals(1_250, rate.increase(1_000))
        assertEquals(750, rate.decrease(1_000))
        assertEquals(1_100, rate.increase(1_000, max = 1_100))
        assertEquals(900, rate.decrease(1_000, min = 900))
    }

    @Test
    fun safeMathCapsResourceChanges() {
        assertEquals(100, GameNumbers.clamp(120, min = 0, max = 100))
        assertEquals(Long.MAX_VALUE, GameNumbers.safeAdd(Long.MAX_VALUE - 1, 10))
        assertEquals(100L, GameNumbers.safeAdd(90L, 20L, max = 100L))
        assertEquals(0L, GameNumbers.safeSubtract(5L, 10L))
        assertEquals(Long.MIN_VALUE, GameNumbers.safeSubtract(Long.MIN_VALUE + 1, 10L, min = Long.MIN_VALUE))
    }

    @Test
    fun rangeValuesSupportContainsClampAndRandom() {
        val intRange = IntRangeValue(10, 20)
        val longRange = LongRangeValue(100, 200)

        assertTrue(intRange.contains(15))
        assertEquals(20, intRange.clamp(30))
        assertTrue(intRange.random(Random(1)) in 10..20)
        assertTrue(longRange.contains(150))
        assertEquals(100, longRange.clamp(50))
        assertTrue(longRange.random(Random(2)) in 100..200)
    }

    @Test
    fun invalidNumericInputsFailFast() {
        assertFailsWith<IllegalArgumentException> {
            IntRangeValue(2, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            GameNumbers.clamp(1, min = 2, max = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            GameNumbers.toIntExact(Int.MAX_VALUE.toLong() + 1)
        }
    }
}
