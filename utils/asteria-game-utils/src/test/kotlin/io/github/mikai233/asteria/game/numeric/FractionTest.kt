package io.github.mikai233.asteria.game.numeric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import java.math.BigDecimal
import java.math.RoundingMode

class FractionTest {
    @Test
    fun fractionKeepsExactPrecisionAcrossOperations() {
        val result = Fraction.of(1, 2) * Fraction.of(1, 3) / 2

        assertEquals(Fraction.of(1, 12), result)
        assertEquals("1/12", result.toString())
    }

    @Test
    fun fractionCanMixIntegerDecimalAndFractionInputs() {
        val result = Fraction.of(1, 2) * Fraction.of(0.3) / 2 + 5

        assertEquals(Fraction.of(203, 40), result)
    }

    @Test
    fun fractionNormalizesSignsAndReducesValues() {
        assertEquals(Fraction.of(-1, 2), Fraction.of(2, -4))
        assertEquals(Fraction.ZERO, Fraction.of(0, -9))
        assertEquals("3", Fraction.of(9, 3).toString())
    }

    @Test
    fun fractionSupportsAdditionSubtractionAndComparison() {
        val value = Fraction.of(1, 2) + Fraction.of(1, 3) - Fraction.of(1, 6)

        assertEquals(Fraction.of(2, 3), value)
        assertTrue(Fraction.of(3, 4) > Fraction.of(2, 3))
    }

    @Test
    fun fractionConvertsOnlyWhenRequested() {
        assertEquals(2, Fraction.of(3, 2).toLong(RoundingMode.HALF_UP))
        assertEquals(BigDecimal("0.3333333333333333333333333333333333"), Fraction.of(1, 3).toBigDecimal())
        assertFailsWith<IllegalArgumentException> {
            Fraction.of(1, 2).toLongExact()
        }
    }

    @Test
    fun fractionCanBeBuiltFromDecimalFloatAndDoubleValues() {
        assertEquals(Fraction.of(1, 10), Fraction.of(0.1))
        assertEquals(Fraction.of(125, 100), Fraction.of(1.25f))
        assertEquals(Fraction.of(1, 10), Fraction.fromDecimal("0.1000"))
    }

    @Test
    fun binaryAndDecimalFloatingPointFactoriesAreExplicit() {
        assertEquals(Fraction.of(1, 10), Fraction.fromDecimal(0.1))
        assertNotEquals(Fraction.of(1, 10), Fraction.fromBinary(0.1))
    }

    @Test
    fun fractionRejectsZeroDivisors() {
        assertFailsWith<IllegalArgumentException> {
            Fraction.of(1, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            Fraction.ONE / Fraction.ZERO
        }
        assertFailsWith<IllegalArgumentException> {
            Fraction.fromBinary(Double.NaN)
        }
    }
}
